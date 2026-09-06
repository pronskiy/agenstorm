package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.io.NioFiles
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

/**
 * Step G2.1, end to end: a body posted the way the shim posts it opens the named files in this project
 * with the caret where the argument asked for, and anything the router declines comes back as 409.
 *
 * Runs off the EDT ([runInDispatchThread] is false) because the endpoint navigates on the EDT — a test
 * body holding the EDT while waiting for the HTTP response would deadlock against it.
 */
class OpenRequestNavigationTest : BasePlatformTestCase() {

    private lateinit var server: OpenRequestServer
    private lateinit var cwd: Path

    override fun runInDispatchThread() = false

    override fun setUp() {
        super.setUp()
        server = project.service<OpenRequestServer>()
        cwd = Files.createTempDirectory("agenstorm-open-nav").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, cwd.toString())
        myFixture.addFileToProject("src/Foo.php", (1..60).joinToString("\n") { "// line $it with some text" })
        myFixture.addFileToProject("docs/notes.md", "# Notes\n\nSecond line.\n")
        assertTrue(server.start() > 0)
    }

    override fun tearDown() {
        try {
            onEdt {
                val manager = FileEditorManager.getInstance(project)
                manager.openFiles.forEach { manager.closeFile(it) }
            }
            server.stop()
            NioFiles.deleteRecursively(cwd)
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testFileWithLineAndColumnOpensWithTheCaretThere() {
        assertEquals(204, open("src/Foo.php:42:7").statusCode())

        val editor = selectedEditor()
        assertEquals("Foo.php", currentFileName())
        assertEquals(LogicalPosition(41, 6), caretOf(editor))
    }

    fun testFileWithoutALocationOpensAtTheTop() {
        assertEquals(204, open("src/Foo.php").statusCode())

        assertEquals(LogicalPosition(0, 0), caretOf(selectedEditor()))
    }

    fun testALineBeyondTheEndOfTheFileLandsOnTheLastLine() {
        assertEquals(204, open("src/Foo.php:99999").statusCode())

        assertEquals(59, caretOf(selectedEditor()).line)
    }

    fun testSeveralFilesAllOpen() {
        assertEquals(204, open("src/Foo.php:3", "docs/notes.md:2").statusCode())

        val open = ReadAction.computeBlocking<List<String>, RuntimeException> {
            FileEditorManager.getInstance(project).openFiles.map { it.name }
        }
        assertContainsElements(open, "Foo.php", "notes.md")
        assertEquals("the last argument ends up in front", "notes.md", currentFileName())
    }

    fun testTheFeatureToggleTurnsTheEndpointIntoAFallback() {
        AgenstormSettings.getInstance().state.terminalOpenEnabled = false

        assertEquals(409, open("src/Foo.php:42").statusCode())
        assertNull(selectedEditorOrNull())
    }

    fun testWhatTheRouterDeclinesComesBackAs409() {
        assertEquals(409, open("nope.txt").statusCode())
        assertEquals(409, open("-a", "Preview").statusCode())
        assertEquals(409, open("https://jetbrains.com").statusCode())
        assertNull(selectedEditorOrNull())
    }

    private fun open(vararg argv: String): HttpResponse<Void> {
        val body = (listOf(server.token, cwd.toString()) + argv)
            .joinToString(separator = "\u0000", postfix = "\u0000")
            .toByteArray(StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}${OpenRequestServer.CONTEXT_PATH}"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            .send(request, HttpResponse.BodyHandlers.discarding())
    }

    private fun selectedEditor(): Editor = selectedEditorOrNull() ?: throw AssertionError("no editor was opened")

    private fun selectedEditorOrNull(): Editor? =
        onEdt { FileEditorManager.getInstance(project).selectedTextEditor }

    private fun currentFileName(): String? = onEdt {
        FileEditorManager.getInstance(project).selectedEditor?.file?.name
    }

    private fun caretOf(editor: Editor): LogicalPosition = onEdt { editor.caretModel.logicalPosition }!!

    private fun <T> onEdt(compute: () -> T): T? {
        var result: T? = null
        ApplicationManager.getApplication().invokeAndWait { result = compute() }
        return result
    }
}
