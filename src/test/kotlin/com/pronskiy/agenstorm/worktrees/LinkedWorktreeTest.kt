package com.pronskiy.agenstorm.worktrees

import com.intellij.openapi.util.io.NioFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** A linked worktree finds its main checkout from the disk; a main checkout, a submodule and a plain folder do not. */
class LinkedWorktreeTest {

    private lateinit var root: Path
    private lateinit var main: Path

    private fun git(dir: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue("git ${args.joinToString(" ")}: $out", process.waitFor() == 0)
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("linked").toRealPath()
        main = Files.createDirectories(root.resolve("app"))
        git(main, "init", "-q", "-b", "main")
        git(main, "-c", "user.email=t@example.com", "-c", "user.name=T", "commit", "-q", "--allow-empty", "-m", "init")
    }

    @After
    fun tearDown() = NioFiles.deleteRecursively(root)

    @Test
    fun aLinkedWorktreeInsideOrBesideFindsItsMainCheckout() {
        git(main, "worktree", "add", "-q", "-b", "fix-login", main.resolve(".worktrees/fix-login").toString())
        git(main, "worktree", "add", "-q", "-b", "hotfix", root.resolve("app-hotfix").toString())

        assertEquals(main, LinkedWorktree.mainCheckout(main.resolve(".worktrees/fix-login")))
        assertEquals(main, LinkedWorktree.mainCheckout(root.resolve("app-hotfix")))
        assertEquals("app", LinkedWorktree.projectName(main))
        assertEquals("app:fix-login", WorktreeProjectNames.label(LinkedWorktree.projectName(main), "fix-login"))
    }

    @Test
    fun theMainCheckoutASubmoduleAndAPlainFolderAreNotLinkedWorktrees() {
        val submodule = Files.createDirectories(main.resolve("lib"))
        Files.createDirectories(main.resolve(".git/modules/lib"))
        Files.writeString(submodule.resolve(".git"), "gitdir: ../.git/modules/lib\n")

        assertNull(LinkedWorktree.mainCheckout(main))
        assertNull(LinkedWorktree.mainCheckout(submodule))
        assertNull(LinkedWorktree.mainCheckout(Files.createDirectories(root.resolve("plain"))))
    }

    @Test
    fun aRenamedMainProjectLendsItsName() {
        Files.createDirectories(main.resolve(".idea"))
        Files.writeString(main.resolve(".idea/.name"), "Shop\n")

        assertEquals("Shop", LinkedWorktree.projectName(main))
    }
}
