package com.pronskiy.agenstorm.core

import com.intellij.openapi.util.io.NioFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Step U1.1: a main checkout and its linked worktrees share one common git dir; a submodule and a plain folder do not. */
class GitCommonDirTest {

    private lateinit var root: Path
    private lateinit var main: Path

    private fun git(dir: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue("git ${args.joinToString(" ")}: $out", process.waitFor() == 0)
    }

    @Before
    fun setUp() {
        // Not resolved through symlinks on purpose: on macOS the temp folder is one, and the answer must not care.
        root = Files.createTempDirectory("common")
        main = Files.createDirectories(root.resolve("app"))
        git(main, "init", "-q", "-b", "main")
        git(main, "-c", "user.email=t@example.com", "-c", "user.name=T", "commit", "-q", "--allow-empty", "-m", "init")
    }

    @After
    fun tearDown() = NioFiles.deleteRecursively(root)

    @Test
    fun aMainCheckoutAndItsWorktreesInsideAndBesideShareOneCommonDir() {
        git(main, "worktree", "add", "-q", "-b", "fix-login", main.resolve(".worktrees/fix-login").toString())
        git(main, "worktree", "add", "-q", "-b", "hotfix", root.resolve("app-hotfix").toString())
        val common = main.resolve(".git").toRealPath()

        assertEquals(common, GitCommonDir.of(main))
        assertEquals(common, GitCommonDir.of(main.resolve(".worktrees/fix-login")))
        assertEquals(common, GitCommonDir.of(root.resolve("app-hotfix")))
        assertFalse(GitCommonDir.ofRoot(main)!!.isLinkedWorktree)
        assertTrue(GitCommonDir.ofRoot(root.resolve("app-hotfix"))!!.isLinkedWorktree)
    }

    @Test
    fun aFolderInsideACheckoutBelongsToItsRepository() {
        git(main, "worktree", "add", "-q", "-b", "fix-login", main.resolve(".worktrees/fix-login").toString())
        val src = Files.createDirectories(main.resolve(".worktrees/fix-login/src/Http"))

        assertEquals(main.resolve(".git").toRealPath(), GitCommonDir.of(src))
        assertNull(GitCommonDir.ofRoot(src))
    }

    @Test
    fun aSubmoduleIsARepositoryOfItsOwnAndAPlainFolderBelongsToNone() {
        val submodule = Files.createDirectories(main.resolve("lib"))
        Files.createDirectories(main.resolve(".git/modules/lib"))
        Files.writeString(submodule.resolve(".git"), "gitdir: ../.git/modules/lib\n")

        assertEquals(main.resolve(".git/modules/lib").toRealPath(), GitCommonDir.of(submodule))
        assertNotEquals(GitCommonDir.of(main), GitCommonDir.of(submodule))
        assertFalse(GitCommonDir.ofRoot(submodule)!!.isLinkedWorktree)
        assertNull(GitCommonDir.of(Files.createDirectories(root.resolve("plain"))))
    }

    @Test
    fun aBrokenDotGitFileIsNoCheckout() {
        val broken = Files.createDirectories(root.resolve("broken"))
        Files.writeString(broken.resolve(".git"), "gitdir: ../nowhere\n")
        val empty = Files.createDirectories(root.resolve("empty"))
        Files.writeString(empty.resolve(".git"), "")

        assertNull(GitCommonDir.ofRoot(broken))
        assertNull(GitCommonDir.ofRoot(empty))
    }
}
