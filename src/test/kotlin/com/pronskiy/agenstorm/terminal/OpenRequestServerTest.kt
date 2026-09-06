package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Step G1.1: the loopback endpoint answers `POST /open` with 204 / 409, refuses a bad token with 403,
 * hands the handler the shell's `$PWD` and the untouched argv, and stays unbound until it is started.
 */
class OpenRequestServerTest : BasePlatformTestCase() {

    private lateinit var server: OpenRequestServer
    private val calls = LinkedBlockingQueue<OpenRequestServer.Request>()
    private var handled = true

    override fun setUp() {
        super.setUp()
        server = project.service<OpenRequestServer>()
        server.handler = OpenRequestHandler { cwd, argv ->
            calls.put(OpenRequestServer.Request(cwd, argv))
            handled
        }
        assertTrue("the endpoint should bind on a free loopback port", server.start() > 0)
    }

    override fun tearDown() {
        try {
            server.stop()
            server.handler = OpenRequestHandler { _, _ -> false }
        } finally {
            super.tearDown()
        }
    }

    fun testHandledCommandAnswers204AndCarriesCwdAndArgv() {
        assertEquals(204, post(fields("/work/dir", "src/Foo.php:42:7")).statusCode())

        val call = nextCall()
        assertEquals(Path.of("/work/dir"), call.cwd)
        assertEquals(listOf("src/Foo.php:42:7"), call.argv)
    }

    fun testArgumentsWithSpacesQuotesAndNewlinesSurviveVerbatim() {
        val awkward = listOf("a file with spaces.md", "quote\"and'more", "two\nlines", "")
        assertEquals(204, post(fields("/work/dir", *awkward.toTypedArray())).statusCode())

        assertEquals(awkward, nextCall().argv)
    }

    fun testNoArgumentsStillReachesTheHandler() {
        assertEquals(204, post(fields("/work/dir")).statusCode())

        assertEquals(emptyList<String>(), nextCall().argv)
    }

    fun testDeclinedCommandAnswers409() {
        handled = false

        assertEquals(409, post(fields("/work/dir", "nope.txt")).statusCode())
        assertEquals(listOf("nope.txt"), nextCall().argv)
    }

    fun testWrongTokenAnswers403AndNeverReachesTheHandler() {
        val response = post(fields("/work/dir", "src/Foo.php"), token = "0".repeat(32))

        assertEquals(403, response.statusCode())
        assertNoCall()
    }

    fun testMissingTokenAnswers403() {
        assertEquals(403, post(fields("/work/dir", "src/Foo.php"), token = null).statusCode())
        assertNoCall()
    }

    fun testShorterTokenPrefixIsNotAccepted() {
        assertEquals(403, post(fields("/work/dir"), token = server.token.take(16)).statusCode())
        assertNoCall()
    }

    fun testGetIsRefused() {
        val request = HttpRequest.newBuilder(endpoint())
            .header(OpenRequestServer.TOKEN_HEADER, server.token)
            .GET()
            .build()

        assertEquals(405, send(request).statusCode())
        assertNoCall()
    }

    fun testEmptyBodyAnswers400() {
        assertEquals(400, post(ByteArray(0)).statusCode())
        assertNoCall()
    }

    fun testBlankWorkingDirectoryAnswers400() {
        assertEquals(400, post(fields("   ", "src/Foo.php")).statusCode())
        assertNoCall()
    }

    fun testTokenIsThirtyTwoHexCharactersAndPortIsFreeChosen() {
        assertTrue(server.token, server.token.matches(Regex("[0-9a-f]{32}")))
        assertTrue(server.port > 0)
    }

    fun testStartIsIdempotentAndStopUnbinds() {
        val port = server.port
        assertEquals(port, server.start())

        server.stop()

        assertEquals(-1, server.port)
    }

    fun testParseRequestDropsOnlyTheTrailingSeparator() {
        // `printf '%s\0' "$PWD" "$@"` always leaves a trailing NUL; an empty argument is a real field.
        val parsed = OpenRequestServer.parseRequest(fields("/work", "a", ""))

        assertEquals(Path.of("/work"), parsed!!.cwd)
        assertEquals(listOf("a", ""), parsed.argv)
    }

    fun testParseRequestAcceptsABodyWithoutATrailingSeparator() {
        val body = "/work\u0000a".toByteArray(StandardCharsets.UTF_8)

        assertEquals(listOf("a"), OpenRequestServer.parseRequest(body)!!.argv)
    }

    private fun fields(vararg values: String): ByteArray =
        values.joinToString(separator = "\u0000", postfix = "\u0000").toByteArray(StandardCharsets.UTF_8)

    private fun endpoint(): URI = URI.create("http://127.0.0.1:${server.port}${OpenRequestServer.CONTEXT_PATH}")

    private fun post(body: ByteArray, token: String? = server.token): HttpResponse<Void> {
        val builder = HttpRequest.newBuilder(endpoint()).POST(HttpRequest.BodyPublishers.ofByteArray(body))
        token?.let { builder.header(OpenRequestServer.TOKEN_HEADER, it) }
        return send(builder.build())
    }

    private fun send(request: HttpRequest): HttpResponse<Void> =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build()
            .send(request, HttpResponse.BodyHandlers.discarding())

    private fun nextCall(): OpenRequestServer.Request =
        calls.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: throw AssertionError("the handler was never called")

    private fun assertNoCall() = assertNull("the handler must not see a refused request", calls.poll())

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
