package com.pronskiy.agenstorm.worktrees

import com.pronskiy.agenstorm.worktrees.WorktreeSnapshots.GitTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Step T1.2: git's worktree list joined with the admin dirs. */
class WorktreeSnapshotsTest {

    private val admin = mapOf(
        "/r/.worktrees/a" to WorktreeAdminDirs.Admin("a", lockReason = "claude agent", createdAt = 100),
        "/r/.worktrees/b" to WorktreeAdminDirs.Admin("b", lockReason = "stale reason", createdAt = 200),
    )

    @Test
    fun treesKeepGitsOrderAndTakeTheirAdminData() {
        val snapshot = WorktreeSnapshots.build(
            commonDir = "/r/.git",
            trees = listOf(
                GitTree("/r", "main", isMain = true, isLocked = false, isPrunable = false),
                GitTree("/r/.worktrees/a", "fix-login", isMain = false, isLocked = true, isPrunable = false),
                GitTree("/r/.worktrees/b", null, isMain = false, isLocked = false, isPrunable = false),
            ),
            admin = admin,
        )

        assertEquals("/r/.git", snapshot.commonDir)
        assertEquals(
            listOf(
                Worktree("/r", "main", isMain = true, isLocked = false, lockReason = null, createdAt = 0),
                Worktree("/r/.worktrees/a", "fix-login", isMain = false, isLocked = true, lockReason = "claude agent", createdAt = 100, adminId = "a"),
                Worktree("/r/.worktrees/b", null, isMain = false, isLocked = false, lockReason = null, createdAt = 200, adminId = "b"),
            ),
            snapshot.worktrees,
        )
    }

    @Test
    fun aPrunableTreeIsDroppedAndOneWithoutAdminDataStillShows() {
        val snapshot = WorktreeSnapshots.build(
            commonDir = null,
            trees = listOf(
                GitTree("/r/.worktrees/gone", "gone", isMain = false, isLocked = false, isPrunable = true),
                GitTree("/elsewhere/c", "c", isMain = false, isLocked = true, isPrunable = false),
            ),
            admin = admin,
        )

        assertNull(snapshot.commonDir)
        assertEquals(listOf(Worktree("/elsewhere/c", "c", isMain = false, isLocked = true, lockReason = null, createdAt = 0)), snapshot.worktrees)
    }
}
