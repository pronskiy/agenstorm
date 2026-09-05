package com.pronskiy.agenstorm.commit

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcsUtil.VcsUtil
import java.io.File

/**
 * Steps D1.2/D1.6: ranking, per-file cap, total budget, stat lines for every file, binary and generated
 * files as stat-only, unversioned files wrapped as additions. Changes are synthetic SimpleContentRevisions.
 */
class DiffCollectorTest : BasePlatformTestCase() {

    private val base: String get() = project.basePath!!

    fun testEveryFileAppearsInTheStatWithStatusAndCounts() {
        val changes = listOf(
            modified("src/Foo.php", "<?php\na\nb\n", "<?php\na\nchanged\nadded\n"),
            added("src/New.php", "<?php\nnew\n"),
            deleted("src/Old.php", "<?php\nold\n"),
        )

        val result = DiffCollector(project).collect(changes)

        assertEquals(listOf("M src/Foo.php (+2 -1)", "A src/New.php (+2 -0)", "D src/Old.php (+0 -2)"), result.stat.lines())
        assertTrue(result.diff.contains("+changed"))
        assertTrue(result.diff.contains("-b"))
        assertEmpty(result.omittedFiles)
    }

    fun testSourceFilesComeBeforeTestsAndDocsAndGeneratedFilesAreStatOnly() {
        val changes = listOf(
            modified("composer.lock", "{\"a\":1}\n", "{\"a\":2}\n"),
            modified("README.md", "# a\n", "# b\n"),
            modified("tests/FooTest.php", "<?php\nt1\n", "<?php\nt2\n"),
            modified("src/Foo.php", "<?php\ns1\n", "<?php\ns2\n"),
            modified("vendor/lib/X.php", "<?php\nv1\n", "<?php\nv2\n"),
        )

        val result = DiffCollector(project).collect(changes)

        val order = listOf("src/Foo.php", "tests/FooTest.php", "README.md").map { result.diff.indexOf("+++ b/$it") }
        assertEquals(order, order.sorted())
        assertTrue(order.all { it >= 0 })
        assertFalse(result.diff.contains("composer.lock"))
        assertFalse(result.diff.contains("vendor/lib/X.php"))
        assertEquals(setOf("composer.lock", "vendor/lib/X.php"), result.omittedFiles.toSet())
        assertTrue(result.stat.lines().any { it == "M composer.lock (generated, not in diff)" })
        assertTrue(result.stat.lines().any { it == "M vendor/lib/X.php (generated, not in diff)" })
        assertEquals(5, result.stat.lines().size)
    }

    fun testBudgetIsHonouredWithALockFileAndAHugeGeneratedFilePresent() {
        val hugeBefore = (1..2_000).joinToString("\n") { "line $it" } + "\n"
        val hugeAfter = (1..2_000).joinToString("\n") { "LINE $it xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" } + "\n"
        assertTrue(hugeAfter.length > 100_000)
        val changes = listOf(
            modified("dist/app.js", hugeBefore, hugeAfter),
            modified("package-lock.json", "{}\n", "{\"x\":1}\n"),
            modified("src/A.php", (1..300).joinToString("\n") { "a$it" }, (1..300).joinToString("\n") { "b$it" }),
            modified("src/B.php", (1..300).joinToString("\n") { "c$it" }, (1..300).joinToString("\n") { "d$it" }),
        )

        val result = DiffCollector(project, maxDiffChars = 60_000).collect(changes)

        assertTrue("diff is ${result.diff.length} chars", result.diff.length <= 60_000)
        assertTrue(result.diff.contains("+++ b/src/A.php"))
        assertTrue(result.diff.contains("+++ b/src/B.php"))
        assertEquals(setOf("dist/app.js", "package-lock.json"), result.omittedFiles.toSet())
        assertEquals(4, result.stat.lines().size)
    }

    fun testPerFileCapTruncatesWithAMarkerAndTotalBudgetOmitsTheRest() {
        val big = { prefix: String -> (1..500).joinToString("\n") { "$prefix$it" } + "\n" }
        val changes = listOf(
            modified("src/A.php", big("a"), big("b")),
            modified("src/B.php", big("c"), big("d")),
            modified("src/C.php", big("e"), big("f")),
        )

        val result = DiffCollector(project, maxDiffChars = 5_000, perFileCap = 2_000).collect(changes)

        assertTrue(result.diff.length <= 5_000)
        assertTrue(result.diff.contains("... [truncated "))
        assertTrue(result.diff.contains("+++ b/src/A.php"))
        assertTrue(result.diff.contains("+++ b/src/B.php"))
        assertFalse(result.diff.contains("+++ b/src/C.php"))
        assertEquals(listOf("src/C.php"), result.omittedFiles)
        assertTrue(result.stat.lines().any { it.startsWith("M src/C.php (") && it.endsWith("over budget, not in diff)") })
    }

    fun testBinaryFilesAreStatOnly() {
        val result = DiffCollector(project).collect(listOf(modified("assets/logo.png", "old", "new")))

        assertEquals("M assets/logo.png (binary)", result.stat)
        assertEquals("", result.diff)
        assertEquals(listOf("assets/logo.png"), result.omittedFiles)
    }

    fun testUnversionedFilesAreWrappedAsAdditions() {
        // Unversioned files come from the local file system under the project base path (not the fixture's temp:// root).
        val ioFile = File(base, "src/Fresh.php")
        FileUtil.writeToFile(ioFile, "<?php\nfresh\n")
        try {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)!!

            val result = DiffCollector(project).collect(emptyList(), unversioned = listOf(VcsUtil.getFilePath(file)))

            assertEquals("A src/Fresh.php (+2 -0)", result.stat)
            assertTrue(result.diff.contains("+fresh"))
        } finally {
            FileUtil.delete(ioFile)
        }
    }

    fun testNoChangesGiveEmptyResult() {
        val result = DiffCollector(project).collect(emptyList())
        assertEquals("", result.stat)
        assertEquals("", result.diff)
        assertEmpty(result.omittedFiles)
    }

    private fun path(relative: String): FilePath = LocalFilePath("$base/$relative", false)

    private fun modified(relative: String, before: String, after: String): Change =
        Change(SimpleContentRevision(before, path(relative), "HEAD"), SimpleContentRevision(after, path(relative), "WORKING"))

    private fun added(relative: String, after: String): Change =
        Change(null, SimpleContentRevision(after, path(relative), "WORKING"))

    private fun deleted(relative: String, before: String): Change =
        Change(SimpleContentRevision(before, path(relative), "HEAD"), null)
}
