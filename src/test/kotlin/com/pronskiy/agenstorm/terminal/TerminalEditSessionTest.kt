package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Step K1.4, end to end: a body posted the way the edit shim posts it opens the file in this project, keeps
 * the caller waiting while it is open, and answers only once the tab is closed — with what the user typed on
 * disk by then, because that is the moment the caller reads the file back.
 *
 * Runs off the EDT ([runInDispatchThread] is false): the request is in flight while this test opens, edits
 * and closes the file on the EDT, which is exactly the shape of the real thing.
 */
class TerminalEditSessionTest : BasePlatformTestCase() {

    private lateinit var server: OpenRequestServer
    private lateinit var cwd: Path

    override fun runInDispatchThread() = false

    override fun setUp() {
        super.setUp()
        server = project.service<OpenRequestServer>()
        cwd = Files.createTempDirectory("agenstorm-edit").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, cwd.toString())
        assertTrue(server.start() > 0)
    }

    override fun tearDown() {
        try {
            closeEverything()
            server.stop()
            NioFiles.deleteRecursively(cwd)
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testTheFileOpensAndTheCallerWaitsForTheTabToClose() {
        val file = write("claude-prompt-0123456789abcdef.md", "the prompt as Claude wrote it\n")

        val response = edit(file.name)

        val opened = awaitOpen(file, response)
        try {
            response.get(700, TimeUnit.MILLISECONDS)
            fail("the caller must stay blocked while the file is open")
        } catch (_: TimeoutException) {
            // What every caller of $EDITOR expects, and what `open -a` fails to do.
        }

        close(opened)

        assertEquals(204, response.get(20, TimeUnit.SECONDS).statusCode())
    }

    fun testWhatWasTypedIsOnDiskTheMomentTheAnswerComes() {
        // Six round trips rather than one. The window between "the VFS holds the new text" and "the file
        // system does" is a few milliseconds wide, and a single pass through it caught the bug that put this
        // test here in about one run out of three.
        repeat(6) { round ->
            val file = write("COMMIT_EDITMSG-$round", "\n# Please enter the commit message\n")
            val message = "feat(terminal): the message the user typed in round $round\n"

            val response = edit(file.name)
            val opened = awaitOpen(file, response)
            setText(opened, message)
            close(opened)

            assertEquals(204, response.get(20, TimeUnit.SECONDS).statusCode())
            // Read first, explain later: building a failure message before the read would widen the very
            // window this test exists to close.
            val onDisk = Files.readString(file.path)

            assertEquals(
                "round $round: the caller reads the file from disk the instant it is answered, so the bytes" +
                    " have to be there by then — VFS says ${String(opened.contentsToByteArray())}",
                message,
                onDisk,
            )
        }
    }

    fun testTheFeatureToggleTurnsTheEndpointIntoAFallback() {
        AgenstormSettings.getInstance().state.terminalEditorEnabled = false
        val file = write("notes.md", "text\n")

        assertEquals(409, edit(file.name).get(20, TimeUnit.SECONDS).statusCode())
        assertTrue(openFiles().isEmpty())
    }

    fun testWhatTheRouterDeclinesComesBackAs409AndOpensNothing() {
        write("notes.md", "text\n")
        Files.createDirectory(cwd.resolve("adirectory"))

        assertEquals(409, edit("not-there.md").get(20, TimeUnit.SECONDS).statusCode())
        assertEquals(409, edit("adirectory").get(20, TimeUnit.SECONDS).statusCode())
        assertEquals(409, edit("+42", "notes.md").get(20, TimeUnit.SECONDS).statusCode())
        assertTrue(openFiles().isEmpty())
    }


    fun testAMaximizedTerminalIsUnmaximizedForTheFile() {
        val step = TerminalEditSession.stepAsideFor(state(maximized = true), terminalHeight = 900, frameHeight = 1000)

        assertEquals(TerminalEditSession.StepAside.UNMAXIMIZED, step)
    }

    /**
     * The case the first version of this missed: a terminal dragged to the top of the window covers the
     * editor without anything ever calling `setMaximized`, so the platform's `isMaximized` says no while the
     * user is looking at a terminal and no file.
     */
    fun testATerminalDraggedOverTheEditorIsHiddenForTheFile() {
        val step = TerminalEditSession.stepAsideFor(state(maximized = false), terminalHeight = 900, frameHeight = 1000)

        assertEquals(TerminalEditSession.StepAside.HIDDEN, step)
    }

    fun testAnOrdinarySplitIsLeftAlone() {
        val half = TerminalEditSession.stepAsideFor(state(maximized = false), terminalHeight = 500, frameHeight = 1000)
        // A frame nobody has laid out yet cannot be measured, so only what the platform states is acted on.
        val unmeasurable = TerminalEditSession.stepAsideFor(state(maximized = false), terminalHeight = 0, frameHeight = 0)

        assertEquals(TerminalEditSession.StepAside.NOTHING, half)
        assertEquals(TerminalEditSession.StepAside.NOTHING, unmeasurable)
    }

    fun testATerminalThatIsNotThereIsNotInTheWay() {
        val hidden = TerminalEditSession.stepAsideFor(state(visible = false, maximized = true), 900, 1000)
        val floating = TerminalEditSession.stepAsideFor(state(docked = false, maximized = true), 900, 1000)

        assertEquals(TerminalEditSession.StepAside.NOTHING, hidden)
        assertEquals(TerminalEditSession.StepAside.NOTHING, floating)
    }

    fun testTheTerminalIsOnlyPutBackWhereWeLeftIt() {
        // Docked, visible, no longer maximized: this is the terminal we stepped past, so it goes back up.
        assertTrue(TerminalEditSession.shouldRestore(state(maximized = false)))
        // Everything else is the user having said something more recent than we did while the file was open.
        assertFalse("hidden while the file was open", TerminalEditSession.shouldRestore(state(visible = false, maximized = false)))
        assertFalse("floated while the file was open", TerminalEditSession.shouldRestore(state(docked = false, maximized = false)))
        assertFalse("already maximized again", TerminalEditSession.shouldRestore(state(maximized = true)))
    }

    private fun state(visible: Boolean = true, maximized: Boolean = false, docked: Boolean = true) =
        TerminalMaximizeToggleAction.TerminalWindowState(visible = visible, maximized = maximized, docked = docked)

    private data class Fixture(val name: String, val path: Path)

    private fun write(name: String, text: String): Fixture {
        val path = cwd.resolve(name)
        Files.writeString(path, text)
        return Fixture(name, path)
    }

    private fun edit(vararg argv: String): CompletableFuture<HttpResponse<Void>> {
        val body = (listOf(server.token, cwd.toString()) + argv)
            .joinToString(separator = "\u0000", postfix = "\u0000")
            .toByteArray(StandardCharsets.UTF_8)
        val request = HttpRequest
            .newBuilder(URI.create("http://127.0.0.1:${server.port}${OpenRequestServer.EDIT_CONTEXT_PATH}"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            .sendAsync(request, HttpResponse.BodyHandlers.discarding())
    }

    private fun awaitOpen(file: Fixture, response: CompletableFuture<HttpResponse<Void>>): VirtualFile {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            openFiles().firstOrNull { it.path == file.path.toString() }?.let { return it }
            // An answer that arrives while nothing is open means the IDE declined: say so instead of timing out.
            if (response.isDone) {
                throw AssertionError("the endpoint answered ${response.get().statusCode()} without opening ${file.name}")
            }
            Thread.sleep(50)
        }
        throw AssertionError("${file.name} never opened in an editor; open files: ${openFiles().map { it.path }}")
    }

    private fun openFiles(): List<VirtualFile> =
        onEdt { FileEditorManager.getInstance(project).openFiles.toList() } ?: emptyList()

    private fun setText(file: VirtualFile, text: String) = onEdt {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        ApplicationManager.getApplication().runWriteAction { document.setText(text) }
    }

    private fun close(file: VirtualFile) = onEdt { FileEditorManager.getInstance(project).closeFile(file) }

    private fun closeEverything() = onEdt {
        val manager = FileEditorManager.getInstance(project)
        manager.openFiles.forEach { manager.closeFile(it) }
    }

    private fun <T> onEdt(compute: () -> T): T? {
        var result: T? = null
        ApplicationManager.getApplication().invokeAndWait { result = compute() }
        return result
    }
}
