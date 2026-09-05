package com.pronskiy.agenstorm.commit.llm

import com.pronskiy.agenstorm.core.AgenstormBundle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/**
 * Anthropic Messages API over raw HTTP + SSE (`POST {baseUrl}/v1/messages`, `stream: true`). Emits the
 * `text_delta` payloads of `content_block_delta` events, ignores thinking/ping/usage events, and turns an
 * `error` event or an HTTP error into an [LlmException]. The key comes from [ApiKeyStore] at call time.
 */
class AnthropicBackend(
    private val apiKey: () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val defaultModel: String = DEFAULT_MODEL,
    private val http: HttpSseClient = HttpSseClient(),
) : LlmBackend {

    override val id: String = ID

    override fun stream(request: LlmRequest): Flow<String> = flow {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw LlmException(AgenstormBundle.message("commit.backend.noKey", "Anthropic"))
        val body = buildJsonObject {
            put("model", request.model?.takeIf { it.isNotBlank() } ?: defaultModel.ifBlank { DEFAULT_MODEL })
            put("max_tokens", request.maxTokens)
            put("stream", true)
            if (request.system.isNotBlank()) put("system", request.system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", request.user)
                }
            }
        }
        val httpRequest = HttpRequest.newBuilder(URI("${baseUrl.trimEnd('/')}/v1/messages"))
            .header("content-type", "application/json")
            .header("x-api-key", key)
            .header("anthropic-version", API_VERSION)
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        SseReader.parse(http.lines(httpRequest)).collect { event ->
            val json = parse(event.data) ?: return@collect
            when (event.event ?: json["type"]?.jsonPrimitive?.contentOrNull) {
                "content_block_delta" -> {
                    val delta = json["delta"]?.jsonObject ?: return@collect
                    if (delta["type"]?.jsonPrimitive?.contentOrNull == "text_delta") {
                        delta["text"]?.jsonPrimitive?.contentOrNull?.let { emit(it) }
                    }
                }
                "error" -> throw LlmException(
                    json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "Anthropic API error",
                )
            }
        }
    }

    override suspend fun validate(): String? = try {
        stream(LlmRequest(system = "", user = "Reply with the single word OK.", model = null, maxTokens = 8)).collect()
        null
    } catch (e: LlmException) {
        e.message
    }

    private fun parse(data: String): JsonObject? = try {
        json.parseToJsonElement(data) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        const val ID = "anthropic"
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        /** Current Sonnet at implementation time (2026-09); a free-text setting overrides it. */
        const val DEFAULT_MODEL = "claude-sonnet-5"
        const val API_VERSION = "2023-06-01"
        private const val REQUEST_TIMEOUT_SECONDS = 120L
        private val json = Json { ignoreUnknownKeys = true }
    }
}
