package com.rhodes.privatechat

import com.rhodes.privatechat.shared.model.NonStreamResponse
import com.rhodes.privatechat.viewmodel.shared.SharedUtils
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 深度思考（DeepSeek thinking）相关的计量与诊断断言。
 *
 * 这一组断言守住三件事：
 * 1. 服务端返回的思维链 token / finish_reason 能被正确读出；
 * 2. 思维链占用单独计量，不会和可见回复的输出混为一谈；
 * 3. 思维链原文不进入任何诊断文案（用户可能导出调试日志）。
 */
class ThinkingUsageAccountingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun readsReasoningTokensAndFinishReasonFromProviderResponse() {
        // 形状取自 OpenAI 兼容返回：usage.completion_tokens_details.reasoning_tokens + choices[0].finish_reason
        val body = """
            {
              "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": "【状态】正常", "reasoning_content": "先想一下再回答。"},
                "finish_reason": "stop"
              }],
              "usage": {
                "prompt_tokens": 100,
                "completion_tokens": 60,
                "total_tokens": 160,
                "completion_tokens_details": {"reasoning_tokens": 40}
              }
            }
        """.trimIndent()
        val parsed = json.decodeFromString<NonStreamResponse>(body)
        assertEquals(40, parsed.usage?.completionTokensDetails?.reasoningTokens)
        assertEquals("stop", parsed.choices?.firstOrNull()?.finishReason)
        assertEquals("先想一下再回答。", parsed.choices?.firstOrNull()?.message?.reasoningContent)
    }

    @Test
    fun missingReasoningDetailStaysNullInsteadOfZero() {
        // 服务商不返回该字段时必须保持 null：0 会被误读成“模型没有思考”。
        val body = """{"choices":[{"message":{"role":"assistant","content":"你好"}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        val parsed = json.decodeFromString<NonStreamResponse>(body)
        assertNull(parsed.usage?.completionTokensDetails?.reasoningTokens)
        assertNull(parsed.choices?.firstOrNull()?.finishReason)
    }

    @Test
    fun truncationIsSurfacedAsFinishReasonLength() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"被截断的回复"},"finish_reason":"length"}]}"""
        val parsed = json.decodeFromString<NonStreamResponse>(body)
        assertEquals("length", parsed.choices?.firstOrNull()?.finishReason)
    }

    @Test
    fun usageSummaryCountsThinkingSeparatelyAndLabelsEstimates() {
        val summary = SharedUtils.ChatUsageSummary()
        // 服务端给了统计：直接采用
        summary.record(
            SharedUtils.ChatCallResult(
                content = "回复", inputTokens = 100, outputTokens = 60,
                promptCacheHitTokens = 80, promptCacheMissTokens = 20,
                reasoningTokens = 40, reasoningChars = 66, finishReason = "stop",
            )
        )
        // 服务端没给统计：按字符上界估算，且必须标明不是服务端数据
        summary.record(
            SharedUtils.ChatCallResult(
                content = "回复2", inputTokens = 100, outputTokens = 50,
                promptCacheHitTokens = 90, promptCacheMissTokens = 10,
                reasoningTokens = null, reasoningChars = 100, finishReason = "stop",
            )
        )
        val text = summary.summary()
        assertTrue("思维链占用要单独列出: $text", text.contains("思维链占用"))
        assertTrue("两次调用都要计入: $text", text.contains("2 次返回思维链"))
        assertTrue("未返回统计时必须标明是估算: $text", text.contains("估算"))
        assertTrue("缓存统计的旧文案不能被破坏: $text", text.contains("提示词缓存Token"))
    }

    @Test
    fun truncatedOutputIsFlaggedInUsageSummary() {
        val summary = SharedUtils.ChatUsageSummary()
        summary.record(
            SharedUtils.ChatCallResult(
                content = "半截", inputTokens = 10, outputTokens = 20,
                promptCacheHitTokens = null, promptCacheMissTokens = null,
                reasoningTokens = 5, reasoningChars = 8, finishReason = "length",
            )
        )
        assertTrue("被截断必须提示", summary.summary().contains("截断"))
    }

    @Test
    fun thinkingOffKeepsTheLegacySummaryText() {
        val summary = SharedUtils.ChatUsageSummary()
        assertEquals("本轮未收到模型用量", summary.summary())
        summary.record(
            SharedUtils.ChatCallResult(
                content = "回复", inputTokens = 10, outputTokens = 5,
                promptCacheHitTokens = 8, promptCacheMissTokens = 2,
            )
        )
        val text = summary.summary()
        assertFalse("没开思考就不该出现思维链字样: $text", text.contains("思维链"))
        assertTrue(text.startsWith("本轮成功返回调用"))
    }

    @Test
    fun reasoningTextNeverLeaksIntoDiagnostics() {
        // 诊断里只允许出现长度与 token 数，不能出现思维链原文。
        val secret = "这一段是模型的内部推理，不该被用户看到"
        val call = SharedUtils.ChatCallResult(
            content = "可见回复", inputTokens = 10, outputTokens = 40,
            promptCacheHitTokens = null, promptCacheMissTokens = null,
            reasoningTokens = null, reasoningChars = secret.length, finishReason = "stop",
        )
        val summary = call.reasoningSummary()
        assertFalse("思维链原文不能出现在诊断里: $summary", summary.contains(secret))
        assertFalse("也不能出现片段", summary.contains(secret.take(6)))
        assertTrue("必须保留可诊断的长度信息: $summary", summary.contains("字符数=${secret.length}"))
    }

    @Test
    fun outputReserveGrowsOnlyForDeepSeekThinking() {
        val reserve = { provider: String, thinking: Boolean ->
            SharedUtils.thinkingAwareOutputReserveTokens(provider, thinking)
        }
        assertEquals("普通模式与旧版一致", 2_000, reserve("deepseek", false))
        assertEquals("深度思考要额外留出思维链空间", 4_000, reserve("deepseek", true))
        assertEquals("开关只对 DeepSeek 生效", 2_000, reserve("ali", true))
        assertEquals("未配置厂商时按普通模式处理", 2_000, reserve("custom", false))
    }

    @Test
    fun outputReserveNeverStarvesTheSmallestPromptBudget() {
        // 设置页允许的最小上下文上限是 5000（SettingsRepository clamp），
        // 预留之后必须仍然留下可用的输入预算，不能把窗口全部吃掉。
        val smallestConfiguredLimit = 5_000
        val maxReserve = SharedUtils.thinkingAwareOutputReserveTokens("deepseek", true)
        assertTrue(
            "最小上限${smallestConfiguredLimit}减去预留${maxReserve}后必须仍有输入空间",
            smallestConfiguredLimit - maxReserve >= 512,
        )
    }
}
