package com.rhodes.privatechat.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

expect fun createPlatformEngine(): HttpClientEngine

fun createHttpClient(): HttpClient = HttpClient(createPlatformEngine()) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        })
    }
    install(HttpTimeout) {
        // Feature-level deadlines own chat timing; transport must not fail first with a generic error.
        // 深度思考开启时私聊模型预算为 180s、群聊为 210s，因此传输层上限必须高于它们，
        // 否则用户只会看到一个通用的网络超时，而不是可诊断的业务超时。
        requestTimeoutMillis = TRANSPORT_TIMEOUT_MS
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = TRANSPORT_TIMEOUT_MS
    }
}

fun createHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(createPlatformEngine()) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = TRANSPORT_TIMEOUT_MS
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = TRANSPORT_TIMEOUT_MS
    }
    block()
}

/**
 * 传输层上限。只放宽、不收紧：各业务阶段仍用自己的预算提前结束请求，
 * 这里只保证“业务还没超时，网络不会先失败”。
 */
private const val TRANSPORT_TIMEOUT_MS = 240_000L
