package com.pronskiy.agenstorm.worktrees.cleanup

import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.Plan
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.Risk
import com.pronskiy.agenstorm.worktrees.cleanup.StaleWorktrees.Candidate
import org.junit.Assert.assertEquals
import org.junit.Test

/** Step T5.3: a cleanup takes only what loses nothing and no window shows, oldest first; the automatic one only what is old enough. */
class StaleWorktreesTest {

    private val day = 24L * 60 * 60 * 1000
    private val clean = Plan.Ready(emptyList(), unlock = false, force = false, branch = "x")

    private fun candidate(name: String, plan: Plan = clean, lastActive: Long = 0, open: Boolean = false, main: Boolean = false) =
        Candidate(Worktree("/work/app/.worktrees/$name", name, main, isLocked = false, lockReason = null, createdAt = 0), plan, lastActive, open)

    @Test
    fun onlyAWorktreeThatLosesNothingAndNoWindowShowsIsRemovableOldestFirst() {
        val split = StaleWorktrees.split(listOf(
            candidate("newer", lastActive = 5 * day),
            candidate("dirty", plan = Plan.Ready(listOf(Risk.Changes(listOf("a.txt"))), false, true, "dirty")),
            candidate("unmerged", plan = Plan.Ready(listOf(Risk.Unmerged(2, "main", "unmerged")), false, false, "unmerged")),
            candidate("agent", plan = Plan.AgentRunning("claude (pid 7)")),
            candidate("running", plan = Plan.Ready(listOf(Risk.Running(listOf("npm"))), false, false, "running")),
            candidate("shown", open = true),
            candidate("older", lastActive = 1 * day),
            candidate("app", main = true),
        ))

        assertEquals(listOf("older", "newer"), split.removable.map { it.name })
        assertEquals(listOf("agent", "dirty", "running", "shown", "unmerged"), split.kept.map { it.name })
    }

    @Test
    fun theAutomaticCleanupTakesOnlyWhatIsUnusedForLongEnough() {
        val now = 40 * day
        val stale = StaleWorktrees.stale(listOf(candidate("old", lastActive = 5 * day), candidate("recent", lastActive = 20 * day), candidate("dirty-old", plan = Plan.Unreadable)), now, days = 30)

        assertEquals(listOf("old"), stale.map { it.name })
    }

    @Test
    fun lastActivityIsTheNewestOfCreationReflogAndTheIdesLastSave() {
        assertEquals(9_000L, StaleWorktrees.lastActive(createdAt = 1_000, reflogWritten = 9_000, workspaceSaved = 5_000))
        assertEquals(5_000L, StaleWorktrees.lastActive(createdAt = 1_000, reflogWritten = null, workspaceSaved = 5_000))
        assertEquals(1_000L, StaleWorktrees.lastActive(createdAt = 1_000, reflogWritten = null, workspaceSaved = null))
    }
}
