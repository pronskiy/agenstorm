package com.pronskiy.agenstorm.worktrees

import com.intellij.openapi.util.io.NioFiles
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.ProjectName
import java.nio.file.Files
import java.nio.file.Path

/** A linked worktree is named `<main checkout>:<worktree>` and placed in its main checkout's folder, asked by folder alone. */
class WorktreeProjectNamesTest : BasePlatformTestCase() {

    private lateinit var root: Path
    private lateinit var main: Path
    private lateinit var worktree: Path

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("names").toRealPath()
        main = Files.createDirectories(root.resolve("happy"))
        worktree = main.resolve(".worktrees/test")
        git(main, "init", "-q", "-b", "main")
        git(main, "-c", "user.email=t@example.com", "-c", "user.name=T", "commit", "-q", "--allow-empty", "-m", "init")
        git(main, "worktree", "add", "-q", "-b", "test", worktree.toString())
    }

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
            NioFiles.deleteRecursively(root)
        } finally {
            super.tearDown()
        }
    }

    fun testALinkedWorktreeIsNamedAfterAndPlacedInItsMainCheckout() {
        val names = WorktreeProjectNames()
        assertEquals(ProjectName("happy", main.toString(), "test"), names.name(worktree.toString()))
        assertEquals("happy:test", names.name(worktree.toString())?.text)
    }

    fun testTheMainCheckoutKeepsItsOwnNameAndIcon() {
        val names = WorktreeProjectNames()
        assertNull(names.name(main.toString()))
    }

    fun testWithWorktreesOffNothingIsRenamed() {
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State(worktreesEnabled = false))
        val names = WorktreeProjectNames()
        assertNull(names.name(worktree.toString()))
    }

    private fun git(dir: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue("git ${args.joinToString(" ")}: $out", process.waitFor() == 0)
    }
}
