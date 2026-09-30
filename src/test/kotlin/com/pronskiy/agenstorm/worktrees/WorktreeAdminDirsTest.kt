package com.pronskiy.agenstorm.worktrees

import com.intellij.openapi.util.io.FileUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Step T1.2: the admin dirs `git worktree add` leaves under `<common git dir>/worktrees/`. */
class WorktreeAdminDirsTest {

    private val root: Path = Files.createTempDirectory("worktree-admin")
    private val worktrees: Path = root.resolve("repo/.git/worktrees")

    private fun admin(id: String, gitdir: String, locked: String? = null) {
        val dir = Files.createDirectories(worktrees.resolve(id))
        Files.writeString(dir.resolve("gitdir"), gitdir)
        if (locked != null) Files.writeString(dir.resolve("locked"), locked)
    }

    private fun sip(path: Path) = FileUtil.toSystemIndependentName(path.toString())

    @Test
    fun anAbsoluteGitdirNamesTheWorktreeAndAnUnlockedOneHasNoReason() {
        val wt = root.resolve("repo/.worktrees/fix-login")
        admin("fix-login", "${wt.resolve(".git")}\n")

        val read = WorktreeAdminDirs.read(worktrees)

        assertEquals(setOf(sip(wt)), read.keys)
        val data = read.getValue(sip(wt))
        assertEquals("fix-login", data.id)
        assertNull(data.lockReason)
        assertTrue(data.createdAt > 0)
    }

    @Test
    fun aRelativeGitdirIsResolvedAgainstTheAdminDir() {
        admin("b", "../../../../wt-b/.git\n")

        assertEquals(setOf(sip(root.resolve("wt-b"))), WorktreeAdminDirs.read(worktrees).keys)
    }

    @Test
    fun theLockReasonIsReadAndAnEmptyOneIsNoReason() {
        admin("agent", "${root.resolve("a/.git")}", locked = "claude agent (pid 4242)\n")
        admin("bare", "${root.resolve("b/.git")}", locked = "")

        val read = WorktreeAdminDirs.read(worktrees)

        assertEquals("claude agent (pid 4242)", read.getValue(sip(root.resolve("a"))).lockReason)
        assertNull(read.getValue(sip(root.resolve("b"))).lockReason)
    }

    @Test
    fun aHalfWrittenAdminDirAndAMissingFolderAreSkipped() {
        Files.createDirectories(worktrees.resolve("no-gitdir"))

        assertEquals(emptyMap<String, WorktreeAdminDirs.Admin>(), WorktreeAdminDirs.read(worktrees))
        assertEquals(emptyMap<String, WorktreeAdminDirs.Admin>(), WorktreeAdminDirs.read(root.resolve("nothing-here")))
    }
}
