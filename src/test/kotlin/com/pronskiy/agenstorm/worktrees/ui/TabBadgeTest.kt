package com.pronskiy.agenstorm.worktrees.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.status.WorktreeStatus

/** Step T3.3: the glyphs after a tab's name and the tooltip lines behind them. */
class TabBadgeTest : BasePlatformTestCase() {

    private fun wt(locked: Boolean = false, reason: String? = null) =
        Worktree("/r/.worktrees/a", "a", isMain = false, isLocked = locked, lockReason = reason, createdAt = 1)

    fun testCleanEvenAndIdleShowsNothing() {
        assertSame(TabBadge.NONE, TabBadge.of(wt(), WorktreeStatus.CLEAN, busyReason = null))
        assertSame(TabBadge.NONE, TabBadge.of(wt(), status = null, busyReason = null))
        assertEquals("", TabBadge.NONE.text)
    }

    fun testEverythingAtOnceInItsOrder() {
        val badge = TabBadge.of(wt(locked = true, reason = "claude agent 4242"), WorktreeStatus(3, 2, 1, "main"), "a command is running in its terminal")

        assertEquals("● ↑2 ↓1 ⚙", badge.text)
        assertEquals(listOf(TabBadge.Kind.DIRTY, TabBadge.Kind.AHEAD, TabBadge.Kind.BEHIND, TabBadge.Kind.WORKING), badge.parts.map { it.kind })
        assertEquals(
            listOf(
                "3 uncommitted changes",
                "2 commits ahead of main",
                "1 commit behind main",
                "Busy: a command is running in its terminal",
                "Locked: claude agent 4242",
            ),
            badge.tooltip,
        )
    }

    fun testOneOfEachSaysItInTheSingular() {
        val badge = TabBadge.of(wt(locked = true), WorktreeStatus(1, 1, 0, "origin/main"), busyReason = null)

        assertEquals("● ↑1 ⚙", badge.text)
        assertEquals(listOf("1 uncommitted change", "1 commit ahead of origin/main", "Locked"), badge.tooltip)
    }

    fun testALockWhoseProcessEndedLosesTheGearButKeepsItsLine() {
        val reason = "claude session t3probe (pid 68643 start Wed Sep 30 15:48:15 2026)"
        val ended = TabBadge.of(wt(locked = true, reason = reason), WorktreeStatus.CLEAN, busyReason = null, lockEnded = true)

        assertEquals("", ended.text)
        assertEquals(listOf("Locked: $reason, a process that has ended"), ended.tooltip)
        assertEquals("\u2699", TabBadge.of(wt(locked = true, reason = reason), WorktreeStatus.CLEAN, busyReason = "a process is still running", lockEnded = true).text)
    }

    fun testCountsWithNothingToCountAgainstAreNotShown() {
        assertSame(TabBadge.NONE, TabBadge.of(wt(), WorktreeStatus(0, 4, 4, against = null), busyReason = null))
    }
}
