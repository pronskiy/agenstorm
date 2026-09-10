package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Step K1.4: what the IDE takes from a `$EDITOR` invocation and what it leaves to the shim's own editor.
 * One existing, writable file and nothing else — every other shape means the caller wanted an editor this
 * bridge is not pretending to be.
 */
class EditCommandRouterTest : BasePlatformTestCase() {

    private lateinit var cwd: Path

    override fun setUp() {
        super.setUp()
        cwd = Files.createTempDirectory("agenstorm-edit-router").toRealPath()
    }

    override fun tearDown() {
        try {
            NioFiles.deleteRecursively(cwd)
        } finally {
            super.tearDown()
        }
    }

    fun testARelativeFileIsResolvedAgainstTheShellsWorkingDirectory() {
        val file = write("COMMIT_EDITMSG")

        assertEquals(EditCommandRouter.Decision.Edit(file), EditCommandRouter.route(cwd, listOf("COMMIT_EDITMSG")))
    }

    fun testAnAbsoluteFileIsTakenAsItIs() {
        val file = write("claude-prompt-0123456789abcdef.md")

        assertEquals(EditCommandRouter.Decision.Edit(file), EditCommandRouter.route(Path.of("/elsewhere"), listOf(file.toString())))
    }

    fun testAPathIsNormalizedBeforeItIsUsed() {
        val file = write("notes.md")

        val decision = EditCommandRouter.route(cwd.resolve("sub"), listOf("../notes.md"))

        assertEquals(EditCommandRouter.Decision.Edit(file), decision)
    }

    fun testANameWithSpacesAndAQuoteIsStillJustAName() {
        val file = write("a file with 'spaces'.md")

        assertEquals(EditCommandRouter.Decision.Edit(file), EditCommandRouter.route(cwd, listOf("a file with 'spaces'.md")))
    }

    fun testEverythingThatIsNotExactlyOneFileIsDeclined() {
        write("one.md")
        write("two.md")

        assertDeclined(EditCommandRouter.route(cwd, emptyList()))
        assertDeclined(EditCommandRouter.route(cwd, listOf("one.md", "two.md")))
    }

    fun testAnOptionIsDeclinedSoTheRealEditorCanHandleIt() {
        write("notes.md")

        assertDeclined(EditCommandRouter.route(cwd, listOf("-R")))
        // vi's "open at line 42": the caller expects vi, and would not get it from us.
        assertDeclined(EditCommandRouter.route(cwd, listOf("+42")))
    }

    fun testAPathThatIsNotAFileOnDiskIsDeclined() {
        Files.createDirectory(cwd.resolve("adirectory"))

        assertDeclined(EditCommandRouter.route(cwd, listOf("not-there.md")))
        assertDeclined(EditCommandRouter.route(cwd, listOf("adirectory")))
        assertDeclined(EditCommandRouter.route(cwd, listOf("")))
    }

    fun testAReadOnlyFileIsDeclinedRatherThanOpenedForNothing() {
        if (SystemInfo.isWindows) return
        val file = write("locked.md")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"))

        assertDeclined(EditCommandRouter.route(cwd, listOf("locked.md")))
    }

    private fun assertDeclined(decision: EditCommandRouter.Decision) {
        assertTrue("expected a decline, got $decision", decision is EditCommandRouter.Decision.Decline)
    }

    private fun write(name: String): Path {
        val file = cwd.resolve(name)
        Files.writeString(file, "the text as the caller wrote it\n")
        return file
    }
}
