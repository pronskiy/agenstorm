package com.pronskiy.agenstorm.worktrees.cleanup

import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.LockState
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.Plan
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.Risk
import org.junit.Assert.assertEquals
import org.junit.Test

/** Steps T4.1 and U4.1: what stops a removal, what the user confirms, and how git is told. */
class RemovalPlanTest {

    private fun facts(
        isMain: Boolean = false,
        branch: String? = "fix/login",
        changes: List<String>? = emptyList(),
        unmerged: Int? = 0,
        lock: LockState = LockState.NONE,
        lockReason: String? = null,
        busy: String? = null,
        running: List<String> = emptyList(),
    ) = RemovalPlan.Facts(isMain, branch, changes, unmerged, base = "main", lock = lock, lockReason = lockReason, busyReason = busy, running = running)

    @Test
    fun aCleanMergedWorktreeIsRemovedWithItsBranch() {
        assertEquals(Plan.Ready(emptyList(), unlock = false, force = false, branch = "fix/login"), RemovalPlan.plan(facts()))
    }

    @Test
    fun theMainCheckoutABusyProjectARunningAgentAndAnUnreadableStatusStopIt() {
        assertEquals(Plan.MainCheckout, RemovalPlan.plan(facts(isMain = true, busy = "a command is running in its terminal")))
        assertEquals(Plan.Busy("a command is running in its terminal"), RemovalPlan.plan(facts(busy = "a command is running in its terminal", lock = LockState.LIVE)))
        assertEquals(Plan.AgentRunning("claude session x (pid 7)"), RemovalPlan.plan(facts(lock = LockState.LIVE, lockReason = "claude session x (pid 7)")))
        assertEquals(Plan.Unreadable, RemovalPlan.plan(facts(changes = null)))
    }

    @Test
    fun changesAreConfirmedAndForcedCommitsAreConfirmedAndKept() {
        val plan = RemovalPlan.plan(facts(changes = listOf("README.md", "src/"), unmerged = 3))

        assertEquals(
            Plan.Ready(listOf(Risk.Changes(listOf("README.md", "src/")), Risk.Unmerged(3, "main", "fix/login")), unlock = false, force = true, branch = "fix/login"),
            plan,
        )
        assertEquals(
            Plan.Ready(listOf(Risk.Unmerged(null, "main", "fix/login")), unlock = false, force = false, branch = "fix/login"),
            RemovalPlan.plan(facts(unmerged = null)),
        )
    }

    @Test
    fun aLockNamingNoProcessIsARiskAndAnEndedOneIsJustLifted() {
        assertEquals(
            Plan.Ready(listOf(Risk.Locked("on a USB stick")), unlock = true, force = false, branch = "fix/login"),
            RemovalPlan.plan(facts(lock = LockState.UNKNOWN, lockReason = "on a USB stick")),
        )
        assertEquals(
            Plan.Ready(emptyList(), unlock = true, force = false, branch = "fix/login"),
            RemovalPlan.plan(facts(lock = LockState.ENDED, lockReason = "claude session x (pid 7 start Wed Sep 30 15:48:15 2026)")),
        )
    }

    @Test
    fun archivingCommitsTheChangesKeepsTheBranchAndIgnoresUnmergedCommits() {
        assertEquals(
            Plan.Ready(emptyList(), unlock = false, force = false, branch = "fix/login", commit = true, deleteBranch = false),
            RemovalPlan.archive(facts(changes = listOf("README.md"), unmerged = 3)),
        )
        assertEquals(
            Plan.Ready(listOf(Risk.Locked("on a USB stick")), unlock = true, force = false, branch = "fix/login", commit = false, deleteBranch = false),
            RemovalPlan.archive(facts(lock = LockState.UNKNOWN, lockReason = "on a USB stick")),
        )
        assertEquals(Plan.Busy("a process is still running"), RemovalPlan.archive(facts(busy = "a process is still running")))
        assertEquals(Plan.NoBranch, RemovalPlan.archive(facts(branch = null)))
    }

    @Test
    fun aDetachedHeadHasNoBranchToDelete() {
        val detached = RemovalPlan.Facts(false, null, emptyList(), unmerged = 2, base = null, lock = LockState.NONE, lockReason = null, busyReason = null)

        assertEquals(Plan.Ready(listOf(Risk.Unmerged(2, null, null)), unlock = false, force = false, branch = null), RemovalPlan.plan(detached))
    }

    @Test
    fun aProgramRunningInItsTerminalsIsConfirmedFirstAndStopped() {
        val plan = RemovalPlan.plan(facts(changes = listOf("a.txt"), running = listOf("✳ Claude Code"))) as Plan.Ready
        val archive = RemovalPlan.archive(facts(running = listOf("npm"))) as Plan.Ready

        assertEquals(listOf(Risk.Running(listOf("✳ Claude Code")), Risk.Changes(listOf("a.txt"))), plan.risks)
        assertEquals(true, plan.stop)
        assertEquals(true, archive.stop)
        assertEquals(false, (RemovalPlan.plan(facts()) as Plan.Ready).stop)
        assertEquals("a live lock still refuses outright", Plan.AgentRunning(null), RemovalPlan.plan(facts(lock = LockState.LIVE, running = listOf("claude"))))
    }
}
