package com.rhodes.privatechat

import com.rhodes.privatechat.shared.knowledge.KnowledgeBaseTextProcessor
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Guards the "知识库增加不了" fixes: a picked file whose provider gives no extension must still import,
 * binary formats must be named in the error, and the size caps must accept a real world-book.
 */
class KnowledgeBaseTextProcessorLimitTest {
    private fun text(chars: Int): String {
        val unit = "设定内容"
        return unit.repeat(chars / unit.length + 1).take(chars)
    }

    @Test
    fun fileWithoutExtensionIsAcceptedAsPlainText() {
        val preview = KnowledgeBaseTextProcessor.prepare("世界书", text(2_000).toByteArray())
        assertTrue("无扩展名的文本文件必须能导入", preview.chunks.isNotEmpty())
    }

    @Test
    fun binaryExtensionIsRejectedWithTheFormatNamed() {
        try {
            KnowledgeBaseTextProcessor.prepare("角色卡.pdf", text(500).toByteArray())
            fail("pdf 应该被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue("报错必须指明格式: ${e.message}", e.message.orEmpty().contains("PDF"))
        }
    }

    @Test
    fun fourHundredThousandCharacterWorldBookIsAccepted() {
        val preview = KnowledgeBaseTextProcessor.prepare("大世界书.txt", text(400_000).toByteArray())
        assertTrue("40 万字世界书必须能导入（旧上限 20 万字 / 500 段会拒绝）", preview.chunks.isNotEmpty())
    }

    @Test
    fun oversizeContentReportsTheActualLength() {
        val oversize = KnowledgeBaseTextProcessor.MAX_CHARACTERS + 5_000
        try {
            KnowledgeBaseTextProcessor.prepareText(text(oversize), "txt")
            fail("超过字数上限的内容应该被拒绝")
        } catch (e: IllegalArgumentException) {
            val message = e.message.orEmpty()
            assertTrue("报错必须带上实际字数 $oversize: $message", message.contains(oversize.toString()))
        }
    }

    @Test
    fun chunkLimitAllowsMoreThanFiveHundredChunks() {
        assertTrue("分段上限必须高于旧的 500 段", KnowledgeBaseTextProcessor.MAX_CHUNKS > 500)
        assertTrue("文件体积上限必须高于旧的 2 MB", KnowledgeBaseTextProcessor.MAX_FILE_BYTES > 2 * 1024 * 1024)
        assertTrue("正文字数上限必须高于旧的 20 万字", KnowledgeBaseTextProcessor.MAX_CHARACTERS > 200_000)
    }
}
