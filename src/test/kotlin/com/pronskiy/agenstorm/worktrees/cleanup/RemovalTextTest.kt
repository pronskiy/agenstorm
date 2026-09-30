package com.pronskiy.agenstorm.worktrees.cleanup

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.Plan
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.Risk
import com.pronskiy.agenstorm.worktrees.cleanup.WorktreeRemover.Outcome

/** Step T4.1: the confirmation names what would be lost; a refusal says why; the balloon says what became of the branch. */
class RemovalTextTest : BasePlatformTestCase() {

    fun testACleanRemovalAsksPlainly() {
        val plan = Plan.Ready(emptyList(), unlock = false, force = false, branch = "fix/login")

        assertEquals("Remove the worktree fix-login? Its folder /r/.worktrees/fix-login is deleted, and the branch fix/login if it is merged.", RemovalText.question("fix-login", "/r/.worktrees/fix-login", plan))
        assertEquals("Remove", RemovalText.confirmButton(plan))
    }

    fun testARiskyRemovalNamesTheFilesTheCommitsAndTheLock() {
        val files = (1..7).map { "f$it.php" }
        val plan = Plan.Ready(listOf(Risk.Changes(files), Risk.Unmerged(3, "main", "fix/login"), Risk.Locked("on a USB stick")), unlock = true, force = true, branch = "fix/login")

        assertEquals(
            "Remove the worktree fix-login? Its folder /r/x is deleted, and the branch fix/login if it is merged.\n\n" +
                "Removing it anyway:\n" +
                "• loses 7 uncommitted changes: f1.php, f2.php, f3.php, f4.php, f5.php, and 2 more\n" +
                "• keeps 3 commits that main lacks on the branch fix/login\n" +
                "• lifts its lock: on a USB stick",
            RemovalText.question("fix-login", "/r/x", plan),
        )
        assertEquals("Remove Anyway", RemovalText.confirmButton(plan))
    }

    fun testADetachedHeadAndAnUncountableBranchSayWhatTheyCan() {
        val detached = Plan.Ready(listOf(Risk.Changes(listOf("a.txt")), Risk.Unmerged(1, null, null)), unlock = false, force = true, branch = null)
        assertEquals(
            "Remove the worktree loose? Its folder /r/loose is deleted.\n\nRemoving it anyway:\n• loses 1 uncommitted change: a.txt\n• loses 1 commit that is on no branch",
            RemovalText.question("loose", "/r/loose", detached),
        )
        assertTrue(RemovalText.question("x", "/r/x", Plan.Ready(listOf(Risk.Unmerged(null, null, "x")), false, false, "x")).endsWith("cannot tell whether its commits are merged"))
    }

    fun testRefusalsAndOutcomes() {
        assertEquals("fix-login was not removed: a command is running in its terminal.", RemovalText.refused("fix-login", Plan.Busy("a command is running in its terminal")))
        assertEquals("fix-login was not removed: it is locked by claude session x (pid 7), which is still running.", RemovalText.refused("fix-login", Plan.AgentRunning("claude session x (pid 7)")))
        assertEquals("The main checkout cannot be removed.", RemovalText.refused("app", Plan.MainCheckout))
        assertNull(RemovalText.refused("x", Plan.Ready(emptyList(), false, false, null)))
        assertEquals("Removed fix-login and its branch fix/login.", RemovalText.outcome("fix-login", Outcome.Removed("fix/login", true)))
        assertEquals("Removed wip. Its branch wip is kept: it is not merged.", RemovalText.outcome("wip", Outcome.Removed("wip", false)))
        assertEquals("Could not remove x: fatal: nope", RemovalText.outcome("x", Outcome.Failed("fatal: nope")))
    }
}
