package com.rhodes.privatechat

import com.rhodes.privatechat.shared.network.allowsDeepSeekThinking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 深度思考的适用范围。
 *
 * 判定原则：只有"玩家能读到的内容创作"允许思考；记忆提取、派遣、自检这类功能性调用必须关闭。
 *
 * 这组断言存在的理由：早期用的是"后台排除名单"，结果动态、评论、日记、分层记忆、Galgame、
 * 自检探针因为没被登记全都跟着开关走了思考模式。其中自检探针用 max_tokens=16/64，
 * 实测在思考模式下思维链会吃光输出预算，content 返回空、finish_reason=length，
 * 于是自检报出"模型不可用"的假故障。
 */
class DeepSeekThinkingScopeTest {

    @Test
    fun playerFacingChatKeepsThinking() {
        listOf(
            "Chat#abc123",                  // 私聊主回复
            "Chat#abc123ContentRetry",      // 私聊内容重试
            "ChatRegenerate",               // 重新生成
            "ChatContinue",                 // 继续说
            "VisionChat",                   // 识图后的角色回复
            "GroupChat#g1",                 // 群聊主回复
            "GroupChatContentRetry#g1",     // 群聊内容重试
        ).forEach { tag ->
            assertTrue("聊天类应当允许思考: $tag", allowsDeepSeekThinking(tag))
        }
    }

    @Test
    fun proactiveMessagesKeepThinking() {
        listOf(
            "ProactivePrivate", "ProactivePrivateContentRetry",
            "ProactivePrivateRepeatRetry", "ProactivePrivateFollowUp",
        ).forEach { tag ->
            assertTrue("主动消息也是发给玩家的内容: $tag", allowsDeepSeekThinking(tag))
        }
    }

    @Test
    fun contentCreationKeepsThinking() {
        listOf(
            "Moment", "MomentContentRetry",            // 动态正文
            "MomentComment", "MomentMention", "MomentReply", // 动态评论与回复
            "Diary", "DiaryContentRetry",              // 日记
        ).forEach { tag ->
            assertTrue("内容创作应当允许思考: $tag", allowsDeepSeekThinking(tag))
        }
    }

    @Test
    fun functionalPipelinesNeverUseThinking() {
        listOf(
            "Memory", "GroupMemory", "MemoryL1_PRIVATE_CHAT", "MemoryL2", "MemoryL3",
            "ChatArchive", "ChatArchiveCompact", "Dispatch", "Mahjong", "Poker",
            "GenPrompt", "AiSupport", "FeatureChat",
            "GalgameStory", "GalgameProgress",
            "ReplySuggestion", "ProblemCheckGroup", "ProblemCheckPrivateStructured",
            "ModelTest",
        ).forEach { tag ->
            assertFalse("功能性调用必须关闭思考: $tag", allowsDeepSeekThinking(tag))
        }
    }

    @Test
    fun unknownAndDefaultTagsDefaultToOff() {
        // 默认标签 "Chat" 是若干内部小调用的兜底值；未登记的新调用点也必须默认关闭。
        listOf("Chat", "", "SomeNewBackgroundTask").forEach { tag ->
            assertFalse("未登记的标签必须默认关闭思考: '$tag'", allowsDeepSeekThinking(tag))
        }
    }
}
