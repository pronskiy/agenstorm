package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Step G1.2, and the Phase G1 guardrail corpus: flags, URLs, no arguments, a missing path, `path:42`,
 * `path:42:7`, several paths, a directory, a path with spaces and one with a newline.
 *
 * The working directory is a real temp directory because the router asks the filesystem, not the VFS,
 * whether an argument is there; the resolver fallback uses the fixture's in-memory project instead.
 */
class OpenCommandRouterTest : BasePlatformTestCase() {

    private lateinit var cwd: Path

    override fun setUp() {
        super.setUp()
        cwd = Files.createTempDirectory("agenstorm-open").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, cwd.toString())
    }

    override fun tearDown() {
        try {
            NioFiles.deleteQuietly(cwd)
        } finally {
            super.tearDown()
        }
    }

    fun testNoArgumentsFallsBack() {
        assertFallback(route(emptyList()))
    }

    fun testFlagsFallBackEvenNextToARealFile() {
        val file = write("Foo.php")

        assertFallback(route(listOf("-a", "Preview", file)))
        assertFallback(route(listOf("-R", file)))
    }

    fun testUrlsFallBack() {
        assertFallback(route(listOf("https://jetbrains.com")))
        assertFallback(route(listOf("file:///etc/hosts")))
        assertFallback(route(listOf("x-devonthink-item://ABC")))
    }

    fun testMissingPathFallsBackSoMacOsKeepsItsOwnError() {
        assertFallback(route(listOf("nope.txt")))
    }

    fun testPlainFileIsClaimedWithoutACaretPosition() {
        write("Foo.php")

        val target = singleTarget(route(listOf("Foo.php")))

        assertEquals("Foo.php", target.file.name)
        assertNull(target.line)
        assertNull(target.column)
    }

    fun testTrailingLineIsPeeled() {
        write("Foo.php")

        val target = singleTarget(route(listOf("Foo.php:42")))

        assertEquals(42, target.line)
        assertNull(target.column)
    }

    fun testTrailingLineAndColumnArePeeled() {
        write("src/Foo.php")

        val target = singleTarget(route(listOf("src/Foo.php:42:7")))

        assertEquals("Foo.php", target.file.name)
        assertEquals(42, target.line)
        assertEquals(7, target.column)
    }

    fun testAbsolutePathIsClaimed() {
        val file = write("src/Foo.php")

        val target = singleTarget(route(listOf("$file:42")))

        assertEquals(42, target.line)
    }

    fun testAbsoluteMissingPathNeverFallsBackToABasenameSearch() {
        myFixture.addFileToProject("src/Foo.php", "<?php\n")

        assertFallback(route(listOf("/definitely/not/here/Foo.php:42")))
    }

    fun testPathWithSpacesIsClaimedWithItsLine() {
        write("a file with spaces.md")

        val target = singleTarget(route(listOf("a file with spaces.md:3")))

        assertEquals("a file with spaces.md", target.file.name)
        assertEquals(3, target.line)
    }

    fun testPathWithANewlineIsClaimed() {
        write("two\nlines.md")

        val target = singleTarget(route(listOf("two\nlines.md")))

        assertEquals("two\nlines.md", target.file.name)
    }

    fun testColonInAFileNameIsNotMistakenForALine() {
        write("report:final.txt")

        assertEquals("report:final.txt", singleTarget(route(listOf("report:final.txt"))).file.name)
    }

    fun testSeveralPathsProduceSeveralTargets() {
        write("a.php")
        write("docs/b.md")

        val decision = route(listOf("a.php:1", "docs/b.md:9:2"))

        val targets = (decision as OpenCommandRouter.Decision.OpenFiles).targets
        assertEquals(listOf("a.php", "b.md"), targets.map { it.file.name })
        assertEquals(listOf(1, 9), targets.map { it.line })
        assertEquals(listOf(null, 2), targets.map { it.column })
    }

    fun testOneUnclaimableArgumentMakesTheWholeCommandFallBack() {
        write("a.php")

        assertFallback(route(listOf("a.php", "nope.txt")))
    }

    fun testDirectoryBecomesOpenProject() {
        Files.createDirectories(cwd.resolve("other-project"))

        val decision = route(listOf("other-project"))

        assertEquals(cwd.resolve("other-project"), (decision as OpenCommandRouter.Decision.OpenProject).path)
    }

    fun testDotIsTheWorkingDirectoryItself() {
        assertEquals(cwd, (route(listOf(".")) as OpenCommandRouter.Decision.OpenProject).path)
    }

    fun testFilesMixedWithADirectoryFallBack() {
        write("a.php")
        Files.createDirectories(cwd.resolve("dir"))

        assertFallback(route(listOf("a.php", "dir")))
    }

    fun testTwoDirectoriesFallBack() {
        Files.createDirectories(cwd.resolve("one"))
        Files.createDirectories(cwd.resolve("two"))

        assertFallback(route(listOf("one", "two")))
    }

    fun testBinaryFileFallsBackUnlessUnknownFileTypesAreAllowed() {
        write("archive.zip")
        assertTrue(
            "the fixture needs a file type the IDE calls binary",
            FileTypeRegistry.getInstance().getFileTypeByFileName("archive.zip").isBinary,
        )

        assertFallback(route(listOf("archive.zip")))
        assertEquals("archive.zip", singleTarget(route(listOf("archive.zip"), unknownFileTypes = true)).file.name)
    }

    fun testProjectRelativePathFallsThroughToTheEpicAResolver() {
        myFixture.addFileToProject("src/deep/Service.php", "<?php\n")

        val target = singleTarget(route(listOf("src/deep/Service.php:12:3")))

        assertEquals("Service.php", target.file.name)
        assertEquals(12, target.line)
        assertEquals(3, target.column)
    }

    fun testWorkingDirectoryWinsOverTheProject() {
        myFixture.addFileToProject("Foo.php", "<?php // in the project\n")
        write("Foo.php")

        val target = singleTarget(route(listOf("Foo.php")))

        assertEquals(ON_DISK_CONTENT, String(target.file.contentsToByteArray()))
    }

    private fun route(argv: List<String>, unknownFileTypes: Boolean = false): OpenCommandRouter.Decision =
        OpenCommandRouter(project, unknownFileTypes).route(cwd, argv)

    private fun singleTarget(decision: OpenCommandRouter.Decision): OpenCommandRouter.FileTarget =
        (decision as OpenCommandRouter.Decision.OpenFiles).targets.single()

    private fun assertFallback(decision: OpenCommandRouter.Decision) =
        assertTrue("expected a fallback but got $decision", decision is OpenCommandRouter.Decision.Fallback)

    /** Creates the file under [cwd] and returns its absolute path. */
    private fun write(relative: String): String {
        val path = cwd.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, ON_DISK_CONTENT)
        return path.toString()
    }

    private companion object {
        const val ON_DISK_CONTENT = "line 1\nline 2\nline 3\n"
    }
}
