package com.pronskiy.agenstorm.commit.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpRequest

/** Step D2.4: the line stream over java.net.http against a local server: happy path, HTTP errors, connection failure, cancellation. */
class HttpSseClientTest {

    private lateinit var server: HttpServer
    private val client = HttpSseClient()

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
    fun streamsResponseLines() = runBlocking {
        server.createContext("/sse") { exchange -> exchange.sse("data: one\n", "\n", "data: two\n", "\n") }

        val lines = client.lines(request("/sse")).toList()

        assertEquals(listOf("data: one", "", "data: two", ""), lines)
    }

    @Test
    fun httpErrorBecomesAnLlmExceptionWithTheApiMessage() {
        server.createContext("/anthropic") { exchange -> exchange.error(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""") }
        server.createContext("/openai") { exchange -> exchange.error(429, """{"error":{"message":"Rate limit reached","type":"rate_limit"}}""") }
        server.createContext("/plain") { exchange -> exchange.error(502, "Bad Gateway") }

        assertEquals("HTTP 401: invalid x-api-key", collectError("/anthropic"))
        assertEquals("HTTP 429: Rate limit reached", collectError("/openai"))
        assertEquals("HTTP 502: Bad Gateway", collectError("/plain"))
    }

    @Test
    fun connectionFailureBecomesAnLlmException() {
        val closedPort = server.address.port
        server.stop(0)

        val error = assertThrows(LlmException::class.java) {
            runBlocking { client.lines(HttpRequest.newBuilder(URI("http://127.0.0.1:$closedPort/x")).GET().build()).toList() }
        }
        assertTrue(error.message!!, error.message!!.startsWith("Cannot connect to http://127.0.0.1:$closedPort/x"))
    }

    @Test
    fun cancellationClosesTheStreamPromptly() {
        server.createContext("/slow") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("data: first\n\n".toByteArray())
            exchange.responseBody.flush()
            Thread.sleep(10_000)
            exchange.close()
        }

        val started = System.currentTimeMillis()
        val lines = runBlocking { client.lines(request("/slow")).take(1).toList() }
        val elapsed = System.currentTimeMillis() - started

        assertEquals(listOf("data: first"), lines)
        assertTrue("took $elapsed ms", elapsed < 5_000)
    }

    private fun collectError(path: String): String? =
        assertThrows(LlmException::class.java) { runBlocking { client.lines(request(path)).toList() } }.message

    private fun request(path: String): HttpRequest =
        HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}$path")).GET().build()

    private fun HttpExchange.sse(vararg chunks: String) {
        responseHeaders.add("Content-Type", "text/event-stream")
        sendResponseHeaders(200, 0)
        responseBody.use { out -> chunks.forEach { out.write(it.toByteArray()); out.flush() } }
    }

    private fun HttpExchange.error(status: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
