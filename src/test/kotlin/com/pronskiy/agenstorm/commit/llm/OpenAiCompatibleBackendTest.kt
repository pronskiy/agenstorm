package com.pronskiy.agenstorm.commit.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/** Steps D2.2/D2.5: chat/completions request shape, delta.content chunks, [DONE], optional bearer key, errors. */
class OpenAiCompatibleBackendTest {

    private lateinit var server: HttpServer
    private val lastRequest = AtomicReference<RecordedRequest?>()
    private val request = LlmRequest(system = "You write commit messages.", user = "Diff:\n+a", model = "gpt-x")

    private class RecordedRequest(val path: String, val headers: Map<String, String>, val body: String)

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
    fun streamsDeltaContentAndSendsTheDocumentedRequest() = runBlocking {
        serve("/v1/chat/completions", 200, fixture("openai-stream.sse"))
        val backend = OpenAiCompatibleBackend(apiKey = { "sk-test" }, baseUrl = "${baseUrl()}/v1")

        val chunks = backend.stream(request).toList()

        assertEquals(listOf("feat: a", "dd thing\n", "\nBody."), chunks)
        val recorded = lastRequest.get()!!
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.headers["Authorization"])
        assertEquals("application/json", recorded.headers["Content-type"])
        val body = Json.parseToJsonElement(recorded.body).jsonObject
        assertEquals("gpt-x", body["model"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("You write commit messages.", messages[0]["content"]!!.jsonPrimitive.content)
        assertEquals("Diff:\n+a", messages[1]["content"]!!.jsonPrimitive.content)
        assertNull(body["max_tokens"])
    }

    @Test
    fun noKeyMeansNoAuthorizationHeaderAndTrailingSlashesAreTolerated() = runBlocking {
        serve("/v1/chat/completions", 200, fixture("openai-stream.sse"))
        OpenAiCompatibleBackend(apiKey = { null }, baseUrl = "${baseUrl()}/v1/").stream(request).toList()

        val recorded = lastRequest.get()!!
        assertEquals("/v1/chat/completions", recorded.path)
        assertFalse(recorded.headers.containsKey("Authorization"))
    }

    @Test
    fun missingModelFailsBeforeAnyRequest() {
        serve("/v1/chat/completions", 200, fixture("openai-stream.sse"))
        val error = assertThrows(LlmException::class.java) {
            runBlocking { OpenAiCompatibleBackend(apiKey = { null }, baseUrl = "${baseUrl()}/v1").stream(request.copy(model = null)).toList() }
        }
        assertEquals(true, error.message!!.contains("model"))
        assertNull(lastRequest.get())
    }

    @Test
    fun httpErrorCarriesTheApiMessage() {
        serve("/v1/chat/completions", 401, """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}""")
        val error = assertThrows(LlmException::class.java) {
            runBlocking { OpenAiCompatibleBackend(apiKey = { "sk-bad" }, baseUrl = "${baseUrl()}/v1").stream(request).toList() }
        }
        assertEquals("HTTP 401: Incorrect API key provided", error.message)
    }

    @Test
    fun midStreamErrorObjectFails() {
        serve("/v1/chat/completions", 200, "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\ndata: {\"error\":{\"message\":\"model overloaded\"}}\n\n")
        val collected = ArrayList<String>()
        val error = assertThrows(LlmException::class.java) {
            runBlocking { OpenAiCompatibleBackend(apiKey = { null }, baseUrl = "${baseUrl()}/v1").stream(request).collect { collected += it } }
        }
        assertEquals(listOf("partial"), collected)
        assertEquals("model overloaded", error.message)
    }

    @Test
    fun validateReportsProblemsOrNull() = runBlocking {
        serve("/v1/chat/completions", 200, fixture("openai-stream.sse"))
        assertNull(OpenAiCompatibleBackend(apiKey = { null }, baseUrl = "${baseUrl()}/v1", defaultModel = "gpt-x").validate())
        assertEquals(true, OpenAiCompatibleBackend(apiKey = { null }, baseUrl = "${baseUrl()}/v1", defaultModel = "").validate()!!.contains("model"))
    }

    private fun baseUrl() = "http://127.0.0.1:${server.address.port}"

    private fun fixture(name: String): String = File("src/test/testData/commit/$name").readText()

    private fun serve(path: String, status: Int, body: String) {
        server.createContext(path) { exchange: HttpExchange ->
            val headers = exchange.requestHeaders.entries.associate { (k, v) -> k to v.joinToString(",") }
            lastRequest.set(RecordedRequest(exchange.requestURI.path, headers, exchange.requestBody.readBytes().toString(Charsets.UTF_8)))
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", if (status == 200) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
