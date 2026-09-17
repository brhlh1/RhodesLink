package com.rhodes.privatechat.shared.modelgateway

import com.rhodes.privatechat.shared.model.ThinkingParam
import com.rhodes.privatechat.shared.network.createHttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenAiCompatVisionGateway(
    private val endpoint: String,
    private val apiKey: String,
    private val modelName: String,
    private val useApiKeyHeader: Boolean = false,
) : VisionGateway {
    private val client = createHttpClient()

    override suspend fun analyzeImage(request: VisionAnalyzeRequest): VisionAnalyzeResponse {
        val response = client.post(endpoint) {
            if (useApiKeyHeader) header("api-key", apiKey) else bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(VisionChatRequest(modelName, listOf(VisionChatMessage(content = listOf(
                VisionPart(type = "image_url", imageUrl = VisionImageUrl(request.imageUrlOrBase64)),
                VisionPart(type = "text", text = request.prompt)
            ))), thinking = visionThinkingParam()))
        }
        val raw = response.bodyAsText()
        if (!response.status.isSuccess()) {
            error("识图服务错误 ${response.status.value}: ${raw.take(500)}")
        }
        val text = runCatching { extractText(raw) }.getOrElse {
            error("识图服务返回了无法解析的响应: ${raw.take(500)}")
        }
        if (text.isBlank()) error("识图服务没有返回文字内容: ${raw.take(500)}")
        return VisionAnalyzeResponse(text)
    }

    /**
     * 识图只是一次工具调用，不是角色回复：DeepSeek 默认开启思考模式，会明显变慢变贵，
     * 而且一旦 content 为空，下面的兜底逻辑会把思维链当成“画面描述”交给角色和用户看。
     * 因此对 DeepSeek 显式关闭思考；其它服务商保持不传该字段（与原行为一致）。
     */
    private fun visionThinkingParam(): ThinkingParam? =
        if (endpoint.contains("deepseek", ignoreCase = true)) ThinkingParam("disabled") else null

    private fun extractText(raw: String): String {
        val message = json.parseToJsonElement(raw).jsonObject["choices"]
            ?.let { it as? JsonArray }
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?: return ""
        val content = when (val value = message["content"]) {
            is JsonPrimitive -> value.textOrEmpty()
            is JsonArray -> value.joinToString("") { part ->
                runCatching { part.jsonObject["text"]?.jsonPrimitive?.textOrEmpty().orEmpty() }.getOrDefault("")
            }
            else -> ""
        }
        // 最后兜底：个别服务商只把答案放在 reasoning_content 里。这里只在 content 完全为空时使用，
        // 并且现在 DeepSeek 已关闭思考，所以不会把思维链当作画面描述展示给用户。
        return content.ifBlank { message["reasoning_content"]?.jsonPrimitive?.textOrEmpty().orEmpty() }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

private fun JsonPrimitive.textOrEmpty(): String = if (this is JsonNull) "" else content

@Serializable private data class VisionChatRequest(
    val model: String,
    val messages: List<VisionChatMessage>,
    val thinking: ThinkingParam? = null,
)
@Serializable private data class VisionChatMessage(val role: String = "user", val content: List<VisionPart>)
@Serializable private data class VisionPart(val type: String, val text: String? = null, @kotlinx.serialization.SerialName("image_url") val imageUrl: VisionImageUrl? = null)
@Serializable private data class VisionImageUrl(val url: String)
