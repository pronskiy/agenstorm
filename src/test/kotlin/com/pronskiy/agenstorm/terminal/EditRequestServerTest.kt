package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CompletableDeferred
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Step K1.3: `POST /edit` speaks the same body, token and 204/409/403 as `POST /open`, and differs in the
 * one way that matters — the answer is held back until the handler says the edit is over. A test that only
 * checked the status codes would pass against a handler that answered at once, which is the bug this whole
 * feature exists to avoid, so the holding is what most of these cases are about.
 *
 * Runs off the EDT: the request is sent from this thread and released from another.
 */
class EditRequestServerTest : BasePlatformTestCase() {

    private lateinit var server: OpenRequestServer
    private val calls = LinkedBlockingQueue<OpenRequestServer.Request>()
    private val done = CompletableDeferred<Boolean>()

    override fun runInDispatchThread() = false

    override fun setUp() {
        super.setUp()
        server = project.service<OpenRequestServer>()
        server.editHandler = OpenRequestHandler { cwd, argv ->
            calls.put(OpenRequestServer.Request(cwd, argv))
            done.await()
        }
        assertTrue("the endpoint should bind on a free loopback port", server.start() > 0)
    }

    override fun tearDown() {
        try {
            done.complete(false)
            server.stop()
            server.resetHandlers()
        } finally {
            super.tearDown()
        }
    }

    fun testTheAnswerWaitsForTheEditToFinish() {
        val response = edit("/work/dir", "COMMIT_EDITMSG")

        val call = calls.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("the endpoint was never reached")
        assertEquals(Path.of("/work/dir"), call.cwd)
        assertEquals(listOf("COMMIT_EDITMSG"), call.argv)
        try {
            response.get(700, TimeUnit.MILLISECONDS)
            fail("the caller must stay blocked while the file is open")
        } catch (_: TimeoutException) {
            // Exactly what a caller of $EDITOR expects: nothing until the editor is done.
        }

        done.complete(true)

        assertEquals(204, response.get(20, TimeUnit.SECONDS).statusCode())
    }

    fun testAnEditTheIdeWillNotTakeAnswers409AtOnce() {
        done.complete(false)

        assertEquals(409, edit("/work/dir", "/etc/hosts").get(20, TimeUnit.SECONDS).statusCode())
        assertNotNull(calls.poll(20, TimeUnit.SECONDS))
    }

    fun testWrongTokenAnswers403AndNeverReachesTheHandler() {
        val response = edit("/work/dir", "COMMIT_EDITMSG", token = "0".repeat(32))

        assertEquals(403, response.get(20, TimeUnit.SECONDS).statusCode())
        assertNull("a request that fails the token check must not open anything", calls.poll(1, TimeUnit.SECONDS))
    }

    fun testOnlyPostIsAnswered() {
        val request = HttpRequest.newBuilder(URI.create(endpoint())).GET().build()

        val response = client().send(request, HttpResponse.BodyHandlers.discarding())

        assertEquals(405, response.statusCode())
        assertNull(calls.poll(1, TimeUnit.SECONDS))
    }

    private fun edit(cwd: String, vararg argv: String, token: String = server.token): CompletableFuture<HttpResponse<Void>> {
        val body = (listOf(token, cwd) + argv)
            .joinToString(separator = "\u0000", postfix = "\u0000")
            .toByteArray(StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create(endpoint()))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return client().sendAsync(request, HttpResponse.BodyHandlers.discarding())
    }

    private fun endpoint() = "http://127.0.0.1:${server.port}${OpenRequestServer.EDIT_CONTEXT_PATH}"

    private fun client(): HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
}
