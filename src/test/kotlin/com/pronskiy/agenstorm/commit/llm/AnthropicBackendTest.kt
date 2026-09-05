package com.pronskiy.agenstorm.commit.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/** Steps D2.1/D2.5: Messages API request shape, text deltas from recorded SSE, error paths, validate(). */
class AnthropicBackendTest {

    private lateinit var server: HttpServer
    private val lastRequest = AtomicReference<RecordedRequest?>()
    private val request = LlmRequest(system = "You write commit messages.", user = "Diff:\n+a", model = null)

    private class RecordedRequest(val headers: Map<String, String>, val body: String)

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun streamsTextDeltasOnlyAndSendsTheDocumentedRequest() = runBlocking {
        serve(200, fixture("anthropic-stream.sse"))
        val backend = AnthropicBackend(apiKey = { "sk-test" }, baseUrl = baseUrl())

        val chunks = backend.stream(request).toList()

        assertEquals(listOf("feat: a", "dd thing\n", "\nBody."), chunks)
        val recorded = lastRequest.get()!!
        assertEquals("sk-test", recorded.headers["X-api-key"])
        assertEquals("2023-06-01", recorded.headers["Anthropic-version"])
        assertEquals("application/json", recorded.headers["Content-type"])
        val body = Json.parseToJsonElement(recorded.body).jsonObject
        assertEquals(AnthropicBackend.DEFAULT_MODEL, body["model"]!!.jsonPrimitive.content)
        assertEquals(1024, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
        assertEquals("You write commit messages.", body["system"]!!.jsonPrimitive.content)
        val message = body["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        assertEquals("Diff:\n+a", message["content"]!!.jsonPrimitive.content)
        assertNull(body["thinking"])
    }

    @Test
    fun requestModelOverridesTheDefault() = runBlocking {
        serve(200, fixture("anthropic-stream.sse"))
        AnthropicBackend(apiKey = { "sk-test" }, baseUrl = baseUrl()).stream(request.copy(model = "claude-opus-5", maxTokens = 512)).toList()

        val body = Json.parseToJsonElement(lastRequest.get()!!.body).jsonObject
        assertEquals("claude-opus-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(512, body["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun httpErrorCarriesTheApiMessage() {
        serve(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        val error = assertThrows(LlmException::class.java) {
            runBlocking { AnthropicBackend(apiKey = { "sk-bad" }, baseUrl = baseUrl()).stream(request).toList() }
        }
        assertEquals("HTTP 401: invalid x-api-key", error.message)
    }

    @Test
    fun midStreamErrorEventFailsAfterThePartialText() {
        serve(200, fixture("anthropic-error-event.sse"))
        val collected = ArrayList<String>()
        val error = assertThrows(LlmException::class.java) {
            runBlocking { AnthropicBackend(apiKey = { "sk-test" }, baseUrl = baseUrl()).stream(request).collect { collected += it } }
        }
        assertEquals(listOf("partial"), collected)
        assertEquals("Overloaded", error.message)
    }

    @Test
    fun missingKeyFailsBeforeAnyRequest() {
        serve(200, fixture("anthropic-stream.sse"))
        val error = assertThrows(LlmException::class.java) {
            runBlocking { AnthropicBackend(apiKey = { "  " }, baseUrl = baseUrl()).stream(request).toList() }
        }
        assertEquals(true, error.message!!.contains("API key"))
        assertNull(lastRequest.get())
    }

    @Test
    fun validateReportsProblemsOrNull() = runBlocking {
        serve(200, fixture("anthropic-stream.sse"))
        assertNull(AnthropicBackend(apiKey = { "sk-test" }, baseUrl = baseUrl()).validate())
        assertEquals(true, AnthropicBackend(apiKey = { null }, baseUrl = baseUrl()).validate()!!.contains("API key"))

        serve(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""", path = "/bad/v1/messages")
        assertEquals("HTTP 401: invalid x-api-key", AnthropicBackend(apiKey = { "sk-bad" }, baseUrl = baseUrl() + "/bad").validate())
    }

    private fun baseUrl() = "http://127.0.0.1:${server.address.port}"

    private fun fixture(name: String): String = File("src/test/testData/commit/$name").readText()

    private fun serve(status: Int, body: String, path: String = "/v1/messages") {
        server.createContext(path) { exchange: HttpExchange ->
            val headers = exchange.requestHeaders.entries.associate { (k, v) -> k to v.joinToString(",") }
            lastRequest.set(RecordedRequest(headers, exchange.requestBody.readBytes().toString(Charsets.UTF_8)))
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", if (status == 200) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
