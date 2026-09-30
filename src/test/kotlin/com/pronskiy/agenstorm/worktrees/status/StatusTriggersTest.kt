package com.pronskiy.agenstorm.worktrees.status

import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.WorktreeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/** Step T3.2: which tabs a batch of file events asks a status for. */
class StatusTriggersTest {

    private fun wt(path: String, main: Boolean = false, id: String? = null) =
        Worktree(path, "b", isMain = main, isLocked = false, lockReason = null, createdAt = 0, adminId = id)

    private val snapshot = WorktreeSnapshot("/r/.git", listOf(
        wt("/r", main = true),
        wt("/r/.worktrees/a", id = "a"),
        wt("/r/.claude/worktrees/b", id = "b1"),
        wt("/elsewhere/c", id = "c"),
    ))

    private fun affected(vararg paths: String) = StatusTriggers.affected(paths.toList(), snapshot)

    @Test
    fun aFileBelongsToTheInnermostWorktree() {
        assertEquals(setOf("/r/.worktrees/a"), affected("/r/.worktrees/a/src/Foo.php"))
        assertEquals(setOf("/r"), affected("/r/src/Foo.php", "/r/.worktrees"))
        assertEquals(setOf("/r/.claude/worktrees/b", "/elsewhere/c"), affected("/r/.claude/worktrees/b/x", "/elsewhere/c"))
        assertEquals(emptySet<String>(), affected("/other/project/file", "/r/.worktrees/a/.git", "/r-sibling/file"))
    }

    @Test
    fun anAdminDirIsItsWorktreeAndTheRestOfTheGitDirIsEveryone() {
        assertEquals(setOf("/r/.worktrees/a"), affected("/r/.git/worktrees/a/index"))
        assertEquals(setOf("/r/.claude/worktrees/b"), affected("/r/.git/worktrees/b1/HEAD"))
        assertEquals(emptySet<String>(), affected("/r/.git/worktrees/new-one/HEAD"))
        assertEquals(snapshot.worktrees.map { it.path }.toSet(), affected("/r/.worktrees/a/f", "/r/.git/refs/heads/main"))
    }

    @Test
    fun oneWorktreeHasNoTabsToUpdate() {
        assertEquals(emptySet<String>(), StatusTriggers.affected(listOf("/r/f", "/r/.git/index"), WorktreeSnapshot("/r/.git", listOf(wt("/r", main = true)))))
    }
}
