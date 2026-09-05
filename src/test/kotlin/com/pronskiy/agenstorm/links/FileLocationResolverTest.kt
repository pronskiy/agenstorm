package com.pronskiy.agenstorm.links

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Step A1.2: candidate order (absolute → containing dir → project base → content roots → unique basename)
 * and line/column → offset conversion. Files live in the light fixture's temp content root.
 */
class FileLocationResolverTest : BasePlatformTestCase() {

    private lateinit var resolver: FileLocationResolver

    override fun getTestDataPath() = "src/test/testData/links/resolver"

    override fun setUp() {
        super.setUp()
        resolver = FileLocationResolver(project)
        myFixture.addFileToProject("src/Foo.php", "<?php\n\n\$a = 1;\n    \$b = 2;\n")
        myFixture.addFileToProject("tests/Foo.php", "<?php\n")
        myFixture.addFileToProject("vendor/pkg/src/Foo.php", "<?php\n")
        myFixture.addFileToProject("src/lib/Unique.php", "<?php\n")
        myFixture.addFileToProject("docs/notes.md", "# notes\n")
    }

    fun testResolvesRelativeToTheContainingDirectoryFirst() {
        val context = myFixture.addFileToProject("tests/README.md", "see Foo.php:1")
        assertEquals(inTempDir("tests/Foo.php"), resolve("Foo.php", context))

        val vendorContext = myFixture.addFileToProject("vendor/pkg/README.md", "see src/Foo.php:1")
        assertEquals(inTempDir("vendor/pkg/src/Foo.php"), resolve("src/Foo.php", vendorContext))
    }

    fun testFallsBackToContentRootRelativePath() {
        val context = myFixture.addFileToProject("docs/deep/nested.md", "see src/Foo.php:1")
        assertEquals(inTempDir("src/Foo.php"), resolve("src/Foo.php", context))
        assertEquals(inTempDir("src/Foo.php"), resolve("./src/Foo.php", context))
    }

    fun testResolvesWithoutAContextFile() {
        assertEquals(inTempDir("docs/notes.md"), resolve("docs/notes.md", null))
    }

    fun testParentDirectoryTraversalFromTheContainingDirectory() {
        val context = myFixture.addFileToProject("docs/index.md", "see ../src/Foo.php:3")
        assertEquals(inTempDir("src/Foo.php"), resolve("../src/Foo.php", context))
    }

    fun testAbsolutePath() {
        val absolute = File(testDataPath, "Absolute.php").absolutePath
        val expected = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolute)
        assertNotNull(expected)
        assertEquals(expected, resolve(absolute, null))
    }

    fun testUniqueBasenameFallback() {
        // Unique.php exists once: found by name even though the written directory is wrong.
        assertEquals(inTempDir("src/lib/Unique.php"), resolve("wrong/dir/Unique.php", null))
        // Foo.php exists three times and the written path matches none of them: ambiguous, nothing resolves.
        assertNull(resolve("elsewhere/Foo.php", null))
        // A written path that is a suffix of exactly one candidate disambiguates.
        assertEquals(inTempDir("vendor/pkg/src/Foo.php"), resolve("pkg/src/Foo.php", null))
    }

    fun testDumbModeKeepsDirectLookupsAndSkipsTheBasenameFallback() {
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertTrue(DumbService.isDumb(project))
            assertEquals(inTempDir("src/Foo.php"), resolve("src/Foo.php", null))
            assertNull(resolve("wrong/dir/Unique.php", null))
        }
        assertEquals(inTempDir("src/lib/Unique.php"), resolve("wrong/dir/Unique.php", null))
    }

    fun testDirectoriesAndMissingFilesResolveToNull() {
        assertNull(resolve("src/lib", null))
        assertNull(resolve("src/Missing.php", null))
        assertNull(resolve("/definitely/not/here.php", null))
    }

    fun testOffsetForLineAndColumn() {
        val file = inTempDir("src/Foo.php")
        // Content: "<?php\n" (0-5) "\n" (6) "$a = 1;\n" (7-14) "    $b = 2;\n" (15-26) "" (27)
        assertEquals(0, resolver.toOffset(file, FileLocation("src/Foo.php", 1, null)))
        assertEquals(7, resolver.toOffset(file, FileLocation("src/Foo.php", 3, null)))
        assertEquals(7, resolver.toOffset(file, FileLocation("src/Foo.php", 3, 1)))
        assertEquals(9, resolver.toOffset(file, FileLocation("src/Foo.php", 3, 3)))
        assertEquals(19, resolver.toOffset(file, FileLocation("src/Foo.php", 4, 5)))
    }

    fun testOffsetClampsOutOfRangeLineAndColumn() {
        val file = inTempDir("src/Foo.php")
        // A column past the end of the line stops at the line end, before the newline.
        assertEquals(14, resolver.toOffset(file, FileLocation("src/Foo.php", 3, 99)))
        // A line past the end of the document lands on the last (empty) line.
        assertEquals(27, resolver.toOffset(file, FileLocation("src/Foo.php", 999, null)))
    }

    private fun resolve(path: String, context: com.intellij.psi.PsiFile?): VirtualFile? =
        resolver.resolve(FileLocation(path, 1, null), context)

    private fun inTempDir(relativePath: String): VirtualFile {
        val file = myFixture.findFileInTempDir(relativePath)
        assertNotNull("fixture file $relativePath is missing", file)
        return file!!
    }
}
