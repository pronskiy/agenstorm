package com.pronskiy.agenstorm.commit.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.io.UncheckedIOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * Streams an HTTP response line by line with `java.net.http`. HTTP ≥ 400 becomes an [LlmException] carrying the
 * API's `error.message` when the body is JSON; connection failures become an [LlmException] too. Cancelling the
 * collector cancels the request future and closes the body stream, which unblocks the reading thread.
 */
class HttpSseClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {

    fun lines(request: HttpRequest): Flow<String> = channelFlow {
        val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
        var body: Stream<String>? = null
        invokeOnClose {
            future.cancel(true)
            body?.close()
        }
        val response = try {
            future.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("Cannot connect to ${request.uri()}: ${rootMessage(e)}", e)
        }
        val stream = response.body()
        body = stream
        if (response.statusCode() >= 400) {
            val text = withContext(Dispatchers.IO) { stream.use { it.collect(Collectors.joining("\n")) } }
            throw LlmException(httpError(response.statusCode(), text))
        }
        withContext(Dispatchers.IO) {
            try {
                stream.use { lines ->
                    val iterator = lines.iterator()
                    while (iterator.hasNext()) send(iterator.next())
                }
            } catch (e: UncheckedIOException) {
                if (!isClosedForSend) throw LlmException("Connection lost: ${rootMessage(e)}", e)
            } catch (e: IOException) {
                if (!isClosedForSend) throw LlmException("Connection lost: ${rootMessage(e)}", e)
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun httpError(status: Int, body: String): String {
            val details = apiErrorMessage(body) ?: body.trim().take(300).ifEmpty { "no details" }
            return "HTTP $status: $details"
        }

        /** `error.message` (Anthropic, OpenAI and most compatible servers) or a plain string `error`. */
        fun apiErrorMessage(body: String): String? = try {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return null
            when (val error = root["error"]) {
                is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> error.contentOrNull
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            null
        }

        fun rootMessage(e: Throwable): String {
            val root = generateSequence(e) { it.cause }.last()
            return root.message?.takeIf { it.isNotBlank() } ?: root.javaClass.simpleName
        }
    }
}
