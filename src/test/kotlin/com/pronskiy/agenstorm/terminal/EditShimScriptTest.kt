package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Step K1.1: what the generated `$EDITOR` shim does before the IDE side of the bridge exists. The endpoint
 * here is a plain [HttpServer] rather than [OpenRequestServer] on purpose — the point of these cases is the
 * script's own contract (it blocks until the answer comes, it never loses the argv, it hands over to a real
 * editor whenever the IDE will not take the file), and that contract has to hold for every reason the IDE
 * might decline, including the 404 it still answers with until step K1.3 binds
 * [OpenRequestServer.EDIT_CONTEXT_PATH].
 */
class EditShimScriptTest : BasePlatformTestCase() {

    private lateinit var holder: OpenShimScriptHolder
    private lateinit var cwd: Path
    private lateinit var shim: Path
    private var endpoint: HttpServer? = null

    override fun setUp() {
        super.setUp()
        holder = service<OpenShimScriptHolder>()
        NioFiles.deleteRecursively(holder.binDir)
        cwd = Files.createTempDirectory("agenstorm-edit").toRealPath()
        shim = holder.install(emptyList(), editShim = true)!!.resolve(OpenShimScriptHolder.EDIT_SHIM_NAME)
    }

    override fun tearDown() {
        try {
            endpoint?.stop(0)
            NioFiles.deleteRecursively(holder.binDir)
            NioFiles.deleteRecursively(cwd)
        } finally {
            super.tearDown()
        }
    }

    fun testTheEditShimStaysInSyncWithTheEndpointItPostsTo() {
        val script = Files.readString(shim)

        assertTrue(script.startsWith("#!/bin/sh\n"))
        assertTrue(script.contains("\$${OpenRequestServer.TOKEN_ENV}\" \"\$PWD\" \"\$@\""))
        assertTrue("the token must not be a curl argument", !script.contains("-H "))
        assertTrue(script.contains("\$${OpenRequestServer.PORT_ENV}${OpenRequestServer.EDIT_CONTEXT_PATH}"))
        assertTrue(script.contains(OpenShimScriptHolder.EDITOR_FALLBACK_ENV))
        assertFalse("POSIX sh has no [[ ]]", script.contains("[["))
        assertFalse("the wait is the user's own pace, so nothing may time it out", script.contains("--max-time"))
        assertFalse("-m is the same timeout under another name", Regex("curl[^\n]* -m ").containsMatchIn(script))
    }

    fun testTheEditShimSendsTheWorkingDirectoryAndArgv() {
        if (SystemInfo.isWindows) return
        val bodies = LinkedBlockingQueue<ByteArray>()
        val port = serve { exchange ->
            bodies.put(exchange.requestBody.readBytes())
            exchange.sendResponseHeaders(204, -1)
        }

        val exitCode = runShim("a file with spaces.md", port = port, token = "s3cret")

        assertEquals("a 204 means the edit is done and the caller may read the file back", 0, exitCode)
        val body = bodies.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("the endpoint was never reached")
        val parsed = OpenRequestServer.parseBody(body) ?: throw AssertionError("unparseable body")
        assertEquals("s3cret", parsed.token)
        assertEquals(cwd, parsed.request.cwd)
        assertEquals(listOf("a file with spaces.md"), parsed.request.argv)
    }

    fun testTheEditShimWaitsForTheAnswerInsteadOfReturningAtOnce() {
        if (SystemInfo.isWindows) return
        val port = serve { exchange ->
            Thread.sleep(HELD_OPEN_MS)
            exchange.sendResponseHeaders(204, -1)
        }

        val started = System.nanoTime()
        val exitCode = runShim("plan.md", port = port, token = "s3cret")
        val elapsed = (System.nanoTime() - started) / 1_000_000

        assertEquals(0, exitCode)
        assertTrue("the shim returned after $elapsed ms, before the IDE answered", elapsed >= HELD_OPEN_MS)
    }

    fun testTheEditShimFallsBackWhenTheIdeDeclines() {
        if (SystemInfo.isWindows) return
        val port = serve { exchange -> exchange.sendResponseHeaders(409, -1) }
        val marker = cwd.resolve("edited-by-the-fallback")

        val exitCode = runShim("plan.md", port = port, token = "s3cret", fallback = fallbackWriting(marker))

        assertEquals(0, exitCode)
        assertEquals("the fallback editor gets the same file", "plan.md\n", Files.readString(marker))
    }

    fun testTheEditShimFallsBackOutsideAnAgenstormTerminal() {
        if (SystemInfo.isWindows) return
        val marker = cwd.resolve("edited-by-the-fallback")

        val exitCode = runShim("plan.md", port = null, token = null, fallback = fallbackWriting(marker))

        assertEquals(0, exitCode)
        assertTrue(Files.isRegularFile(marker))
    }

    fun testTheEditShimFallsBackWithNothingToEdit() {
        if (SystemInfo.isWindows) return
        val marker = cwd.resolve("edited-by-the-fallback")
        val port = serve { exchange -> exchange.sendResponseHeaders(204, -1) }

        val exitCode = runShim(port = port, token = "s3cret", fallback = fallbackWriting(marker))

        assertEquals(0, exitCode)
        assertEquals("no file, nothing for the IDE to open", "\n", Files.readString(marker))
    }

    /** A fallback editor that records the arguments it was handed. Two words, so the word split is tested too. */
    private fun fallbackWriting(marker: Path): String {
        val script = cwd.resolve("fake-editor.sh")
        Files.writeString(script, "#!/bin/sh\necho \"\$@\" > \"$marker\"\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return "/bin/sh $script"
    }

    private fun serve(handler: (HttpExchange) -> Unit): Int {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext(OpenRequestServer.EDIT_CONTEXT_PATH) { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
        endpoint = server
        return server.address.port
    }

    private fun runShim(vararg argv: String, port: Int?, token: String?, fallback: String? = null): Int {
        val process = ProcessBuilder(listOf("/bin/sh", shim.toString()) + argv)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .apply {
                environment().remove("PWD")
                environment().remove(OpenShimScriptHolder.EDITOR_FALLBACK_ENV)
                port?.let { environment()[OpenRequestServer.PORT_ENV] = it.toString() }
                token?.let { environment()[OpenRequestServer.TOKEN_ENV] = it }
                fallback?.let { environment()[OpenShimScriptHolder.EDITOR_FALLBACK_ENV] = it }
            }
            .start()
        process.inputStream.use { it.readBytes() }
        assertTrue("the shim should not hang", process.waitFor(30, TimeUnit.SECONDS))
        return process.exitValue()
    }

    private companion object {
        /** Long enough to tell a held-open answer from an immediate one, short enough not to slow the suite. */
        const val HELD_OPEN_MS = 700L
    }
}
