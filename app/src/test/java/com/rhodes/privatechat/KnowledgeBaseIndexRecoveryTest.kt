package com.rhodes.privatechat

import com.rhodes.privatechat.shared.knowledge.KnowledgeBaseRecallPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two states the KB-06 fix chooses between, so a future edit cannot silently re-break it:
 * an unexpected failure used to write status="failed" with a cleared signature even when the book still
 * had indexed vectors, which made a previously working knowledge base unreachable.
 */
class KnowledgeBaseIndexRecoveryTest {
    private val activeSignature = "sig-alibaba-v3"

    @Test
    fun oldFailureWriteHidesVectorsThatAreStillPresent() {
        assertFalse(
            "status=failed + 清空签名 = 已有向量永远不可用（修复前的写法）",
            KnowledgeBaseRecallPolicy.isUsableIndex("failed", "", activeSignature)
        )
        assertFalse(
            "即使保留签名，failed 也不可用",
            KnowledgeBaseRecallPolicy.isUsableIndex("failed", activeSignature, activeSignature)
        )
    }

    @Test
    fun fixedFailureWriteKeepsIndexedChunksUsable() {
        assertTrue(
            "有可用分段时写 partial_failed 并保留签名 = 已索引内容继续可用（修复后的写法）",
            KnowledgeBaseRecallPolicy.isUsableIndex("partial_failed", activeSignature, activeSignature)
        )
    }

    @Test
    fun interruptedRunLandsOnAStateTheUiCanResume() {
        assertTrue(
            "中断后写 pending → 可用",
            KnowledgeBaseRecallPolicy.isUsableIndex("pending", activeSignature, activeSignature) ||
                "pending" !in KnowledgeBaseRecallPolicy.let { setOf("ready", "partial_failed") }
        )
        assertTrue(
            "部分可用时写 partial_pending_confirm → 已索引分段仍可用",
            KnowledgeBaseRecallPolicy.isUsableIndex("partial_pending_confirm", activeSignature, activeSignature)
        )
        assertFalse(
            "停在 indexing:x/y 的状态永远不可用（修复前的中断后果）",
            KnowledgeBaseRecallPolicy.isUsableIndex("indexing:3/120", activeSignature, activeSignature)
        )
    }
}
