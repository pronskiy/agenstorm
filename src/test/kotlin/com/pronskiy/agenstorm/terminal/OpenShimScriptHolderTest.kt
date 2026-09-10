package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Step G1.3: one executable shim per configured command name, rewritten when the script or the names
 * change and never otherwise, plus a live round trip proving the generated script reaches the G1.1
 * endpoint with the shell's working directory and the untouched argv.
 *
 * Step K1.1 adds the `$EDITOR` shim to the same directory: the two features are switched on and off
 * independently, so what matters here is that each one's files come and go on their own. What the edit
 * script itself does is [EditShimScriptTest].
 */
class OpenShimScriptHolderTest : BasePlatformTestCase() {

    private lateinit var holder: OpenShimScriptHolder
    private lateinit var cwd: Path

    override fun setUp() {
        super.setUp()
        holder = service<OpenShimScriptHolder>()
        NioFiles.deleteRecursively(holder.binDir)
        cwd = Files.createTempDirectory("agenstorm-shim").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, cwd.toString())
    }

    override fun tearDown() {
        try {
            NioFiles.deleteRecursively(holder.binDir)
            NioFiles.deleteRecursively(cwd)
        } finally {
            super.tearDown()
        }
    }

    fun testParseCommandNamesSplitsTrimsAndDropsWhatCannotBeAFileName() {
        val names = OpenShimScriptHolder.parseCommandNames(" open , edit ,, e, open , ../evil, .hidden, a/b, .")

        assertEquals(listOf("open", "edit", "e"), names)
    }

    fun testInstallWritesOneExecutableShimPerName() {
        val dir = holder.install(listOf("open", "e"), editShim = false)!!

        assertEquals(dir, holder.binDir)
        for (name in listOf("open", "e")) {
            val shim = dir.resolve(name)
            assertTrue("$name should be there", Files.isRegularFile(shim))
            assertTrue("$name should be executable", Files.isExecutable(shim))
            assertTrue(Files.readString(shim).startsWith("#!/bin/sh\n"))
        }
    }

    fun testShimStaysInSyncWithTheEndpointItPostsTo() {
        val script = Files.readString(holder.install(listOf("open"), editShim = false)!!.resolve("open"))

        assertTrue(script.contains("\$${OpenRequestServer.TOKEN_ENV}\" \"\$PWD\" \"\$@\""))
        assertTrue("the token must not be a curl argument", !script.contains("-H "))
        assertTrue(script.contains("\$${OpenRequestServer.PORT_ENV}${OpenRequestServer.CONTEXT_PATH}"))
        assertFalse("POSIX sh has no [[ ]]", script.contains("[["))
    }

    fun testUnchangedInstallLeavesTheFilesAlone() {
        val shim = holder.install(listOf("open"), editShim = false)!!.resolve("open")
        Files.writeString(shim, "# touched by hand\n")

        holder.install(listOf("open"), editShim = false)

        assertEquals("# touched by hand\n", Files.readString(shim))
    }

    fun testADeletedShimIsWrittenAgain() {
        val shim = holder.install(listOf("open"), editShim = false)!!.resolve("open")
        Files.delete(shim)

        holder.install(listOf("open"), editShim = false)

        assertTrue(Files.isRegularFile(shim))
    }

    fun testANameTheUserRemovedStopsShadowing() {
        val dir = holder.install(listOf("open", "edit"), editShim = false)!!
        assertTrue(Files.isRegularFile(dir.resolve("edit")))

        holder.install(listOf("open"), editShim = false)

        assertTrue(Files.isRegularFile(dir.resolve("open")))
        assertFalse("edit is no longer configured", Files.exists(dir.resolve("edit")))
    }

    fun testNoUsableNameInstallsNothing() {
        assertNull(holder.install(listOf("../evil", ""), editShim = false))
        assertFalse(Files.exists(holder.binDir))
    }

    fun testTheEditShimIsWrittenBesideTheOpenOnes() {
        val dir = holder.install(listOf("open"), editShim = true)!!

        val editShim = dir.resolve(OpenShimScriptHolder.EDIT_SHIM_NAME)
        assertTrue(Files.isRegularFile(editShim))
        assertTrue(Files.isExecutable(editShim))
        assertTrue("the two shims are different scripts", Files.readString(editShim) != Files.readString(dir.resolve("open")))
    }

    fun testTheEditShimNeedsNoOpenNameOfItsOwn() {
        val dir = holder.install(emptyList(), editShim = true)!!

        assertTrue(Files.isRegularFile(dir.resolve(OpenShimScriptHolder.EDIT_SHIM_NAME)))
        assertFalse("the `open` feature is off, so nothing shadows `open`", Files.exists(dir.resolve("open")))
    }

    fun testTheEditShimGoesAwayWithItsFeature() {
        val dir = holder.install(listOf("open"), editShim = true)!!
        assertTrue(Files.isRegularFile(dir.resolve(OpenShimScriptHolder.EDIT_SHIM_NAME)))

        holder.install(listOf("open"), editShim = false)

        assertTrue(Files.isRegularFile(dir.resolve("open")))
        assertFalse(Files.exists(dir.resolve(OpenShimScriptHolder.EDIT_SHIM_NAME)))
    }

    fun testNeitherFeatureInstallsNothing() {
        assertNull(holder.install(emptyList(), editShim = false))
        assertFalse(Files.exists(holder.binDir))
    }

    fun testAnOpenNameThatCollidesWithTheEditShimGetsTheEditShim() {
        val dir = holder.install(listOf("open", OpenShimScriptHolder.EDIT_SHIM_NAME), editShim = true)!!

        val collided = Files.readString(dir.resolve(OpenShimScriptHolder.EDIT_SHIM_NAME))
        assertFalse("an `open` copy under that name could never be reached through \$EDITOR", collided == Files.readString(dir.resolve("open")))
    }

    fun testTheGeneratedShimPostsTheWorkingDirectoryAndArgvToTheEndpoint() {
        if (SystemInfo.isWindows) return
        val calls = LinkedBlockingQueue<OpenRequestServer.Request>()
        val server = project.service<OpenRequestServer>()
        server.handler = OpenRequestHandler { requestCwd, argv ->
            calls.put(OpenRequestServer.Request(requestCwd, argv))
            true
        }
        try {
            assertTrue(server.start() > 0)
            val shim = holder.install(listOf("open"), editShim = false)!!.resolve("open")

            val exitCode = runShim(shim, "src/Foo.php:42:7", "a file with spaces.md", port = server.port, token = server.token)

            assertEquals("the shim should report success when the IDE claimed the command", 0, exitCode)
            val call = calls.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("the endpoint was never reached")
            assertEquals(cwd, call.cwd)
            assertEquals(listOf("src/Foo.php:42:7", "a file with spaces.md"), call.argv)
        } finally {
            server.stop()
            server.handler = OpenRequestHandler { _, _ -> false }
        }
    }

    fun testTheShimDoesNothingOutsideAnAgenstormTerminal() {
        if (SystemInfo.isWindows) return
        val shim = holder.install(listOf("open"), editShim = false)!!.resolve("open")

        // No port in the environment: the shim must exec the real `open`, which fails on a missing file.
        assertFalse(0 == runShim(shim, "definitely-not-there.txt", port = null, token = null))
    }

    private fun runShim(shim: Path, vararg argv: String, port: Int?, token: String?): Int {
        val process = ProcessBuilder(listOf("/bin/sh", shim.toString()) + argv)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .apply {
                environment().remove("PWD")
                port?.let { environment()["AGENSTORM_OPEN_PORT"] = it.toString() }
                token?.let { environment()["AGENSTORM_OPEN_TOKEN"] = it }
            }
            .start()
        process.inputStream.use { it.readBytes() }
        assertTrue("the shim should not hang", process.waitFor(20, TimeUnit.SECONDS))
        return process.exitValue()
    }
}
