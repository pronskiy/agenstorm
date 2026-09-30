package com.pronskiy.agenstorm.worktrees.merge

import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.LockState
import com.pronskiy.agenstorm.worktrees.merge.MergePlan.Plan
import com.pronskiy.agenstorm.worktrees.merge.MergePlan.Step
import com.pronskiy.agenstorm.worktrees.merge.MergePlan.Strategy
import org.junit.Assert.assertEquals
import org.junit.Test

/** Step T4.3: what stops a merge back, and the git steps of each strategy. */
class MergePlanTest {

    private fun facts(
        isMain: Boolean = false,
        branch: String? = "fix-login",
        changes: List<String>? = emptyList(),
        base: String? = "main",
        baseWorktree: String? = "/r",
        baseChanges: List<String>? = emptyList(),
        ahead: Int? = 2,
        lock: LockState = LockState.NONE,
        busy: String? = null,
    ) = MergePlan.Facts(isMain, branch, changes, base, baseWorktree, baseChanges, ahead, lock, lockReason = "claude session x (pid 7)", busyReason = busy)

    @Test
    fun aCleanBranchAheadOfACleanBaseIsReady() {
        assertEquals(Plan.Ready("fix-login", "main", "/r", 2, emptyList()), MergePlan.plan(facts()))
    }

    @Test
    fun whatStopsIt() {
        assertEquals(Plan.MainCheckout, MergePlan.plan(facts(isMain = true)))
        assertEquals(Plan.NoBranch, MergePlan.plan(facts(branch = null)))
        assertEquals(Plan.Busy("a process is still running"), MergePlan.plan(facts(busy = "a process is still running")))
        assertEquals(Plan.AgentRunning("claude session x (pid 7)"), MergePlan.plan(facts(lock = LockState.LIVE)))
        assertEquals(Plan.NoBase, MergePlan.plan(facts(base = null)))
        assertEquals(Plan.NoBase, MergePlan.plan(facts(base = "fix-login")))
        assertEquals(Plan.BaseNotCheckedOut("main"), MergePlan.plan(facts(baseWorktree = null)))
        assertEquals(Plan.BaseDirty("main", "/r", listOf("composer.lock")), MergePlan.plan(facts(baseChanges = listOf("composer.lock"))))
        assertEquals(Plan.NothingToMerge("main"), MergePlan.plan(facts(ahead = 0)))
        assertEquals(Plan.Unreadable, MergePlan.plan(facts(changes = null)))
        assertEquals(Plan.Unreadable, MergePlan.plan(facts(ahead = null)))
    }

    @Test
    fun uncommittedChangesAreCommittedFirstEvenWithNothingAhead() {
        val plan = MergePlan.plan(facts(changes = listOf("a.php"), ahead = 0)) as Plan.Ready

        assertEquals(
            listOf(
                Step(false, listOf("add", "-A")),
                Step(false, listOf("commit", "--no-verify", "-q", "-m", "wip: merge back fix-login")),
                Step(true, listOf("merge", "--squash", "fix-login")),
            ),
            MergePlan.steps(plan, Strategy.SQUASH, "fix-login"),
        )
    }

    @Test
    fun rebaseRewritesTheWorktreeThenFastForwardsTheBase() {
        val plan = MergePlan.plan(facts()) as Plan.Ready

        assertEquals(
            listOf(Step(false, listOf("rebase", "main")), Step(true, listOf("merge", "--ff-only", "fix-login"))),
            MergePlan.steps(plan, Strategy.REBASE, "fix-login"),
        )
    }
}
