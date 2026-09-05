package com.pronskiy.agenstorm.commit.llm

import com.pronskiy.agenstorm.core.AgenstormBundle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/**
 * Any OpenAI-compatible chat completions endpoint (`POST {baseUrl}/chat/completions`, `stream: true`):
 * OpenAI, Ollama (`http://localhost:11434/v1`), LM Studio, OpenRouter, Groq. Emits `choices[0].delta.content`
 * chunks until `[DONE]`; a mid-stream `error` object or an HTTP error becomes an [LlmException]. The bearer key is
 * sent only when one is stored. No token limit is sent: servers disagree on `max_tokens` vs `max_completion_tokens`,
 * and a commit message is short anyway. A model is required.
 */
class OpenAiCompatibleBackend(
    private val apiKey: () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val defaultModel: String = "",
    private val http: HttpSseClient = HttpSseClient(),
) : LlmBackend {

    override val id: String = ID

    override fun stream(request: LlmRequest): Flow<String> = flow {
        val model = request.model?.takeIf { it.isNotBlank() } ?: defaultModel.takeIf { it.isNotBlank() }
            ?: throw LlmException(AgenstormBundle.message("commit.backend.openai.noModel"))
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                if (request.system.isNotBlank()) {
                    addJsonObject {
                        put("role", "system")
                        put("content", request.system)
                    }
                }
                addJsonObject {
                    put("role", "user")
                    put("content", request.user)
                }
            }
        }
        val builder = HttpRequest.newBuilder(URI("${baseUrl.trimEnd('/')}/chat/completions"))
            .header("content-type", "application/json")
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        apiKey()?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.header("authorization", "Bearer $it") }

        SseReader.parse(http.lines(builder.build())).collect { event ->
            val json = parse(event.data) ?: return@collect
            json["error"]?.let { error ->
                val message = (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: error.jsonPrimitive.contentOrNull ?: "API error"
                throw LlmException(message)
            }
            val delta = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta") ?: return@collect
            val content = delta.jsonObject["content"]
            if (content != null && content != JsonNull) content.jsonPrimitive.contentOrNull?.takeIf { it.isNotEmpty() }?.let { emit(it) }
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
        const val ID = "openai"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val REQUEST_TIMEOUT_SECONDS = 120L
        private val json = Json { ignoreUnknownKeys = true }
    }
}
