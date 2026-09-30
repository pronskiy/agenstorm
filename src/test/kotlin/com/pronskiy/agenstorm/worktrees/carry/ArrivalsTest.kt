package com.pronskiy.agenstorm.worktrees.carry

import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.WorktreeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/** Step T4.8: which worktrees have just appeared, from one registry snapshot to the next. */
class ArrivalsTest {

    private val now = 10_000_000L
    private val main = Worktree("/r", "main", isMain = true, isLocked = false, lockReason = null, createdAt = 0)
    private fun wt(name: String, created: Long) = Worktree("/r/.worktrees/$name", name, isMain = false, isLocked = false, lockReason = null, createdAt = created)
    private fun snapshot(vararg worktrees: Worktree, common: String = "/r/.git") = WorktreeSnapshot(common, worktrees.toList())

    @Test
    fun aNewRecentWorktreeHasArrived() {
        val fresh = wt("claude-x", now - 1_000)

        assertEquals(listOf(fresh), Arrivals.between(snapshot(main, wt("a", 1)), snapshot(main, wt("a", 1), fresh), now))
    }

    @Test
    fun theFirstSnapshotAnotherRepositoryAndAnOldWorktreeAreNoArrivals() {
        val fresh = wt("claude-x", now - 1_000)

        assertEquals(emptyList<Worktree>(), Arrivals.between(WorktreeSnapshot.EMPTY, snapshot(main, fresh), now))
        assertEquals(emptyList<Worktree>(), Arrivals.between(snapshot(main, common = "/other/.git"), snapshot(main, fresh), now))
        assertEquals(emptyList<Worktree>(), Arrivals.between(snapshot(main), snapshot(main, wt("old", now - Arrivals.RECENT_MS - 1)), now))
        assertEquals(emptyList<Worktree>(), Arrivals.between(snapshot(wt("a", 1)), snapshot(wt("a", 1), main.copy(createdAt = now)), now))
    }
}
