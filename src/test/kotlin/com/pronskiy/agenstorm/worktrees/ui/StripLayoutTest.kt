package com.pronskiy.agenstorm.worktrees.ui

import com.pronskiy.agenstorm.worktrees.ui.StripLayout.Plan
import org.junit.Assert.assertEquals
import org.junit.Test

/** Step T1.5: which worktree tabs fit, and the current one is never left out. */
class StripLayoutTest {

    private val widths = listOf(50, 60, 70, 80, 90)

    private fun plan(current: Int, available: Int) = StripLayout.plan(widths, current, available, gap = 2, fixed = 20, more = 22)

    @Test
    fun everythingFitsSoNothingOverflows() {
        assertEquals(Plan(listOf(0, 1, 2, 3, 4), emptyList()), plan(current = 0, available = 350 + 10 + 20))
    }

    @Test
    fun tabsThatDoNotFitGoToTheOverflowInOrder() {
        // 300 - 20 (+) - 22 (») = 258 → 52 + 62 + 72 = 186, + 82 = 268 does not fit.
        assertEquals(Plan(listOf(0, 1, 2), listOf(3, 4)), plan(current = 1, available = 300))
    }

    @Test
    fun theCurrentTabTakesThePlaceOfTheLastOnesThatFit() {
        // Budget 258: 0, 1, 2 fit (186, 72 left); the current tab 4 needs 92, so 2 goes (144 left) and 4 comes in.
        assertEquals(Plan(listOf(0, 1, 4), listOf(2, 3)), plan(current = 4, available = 300))
    }

    @Test
    fun withNoRoomTheCurrentTabStillShows() {
        assertEquals(Plan(listOf(3), listOf(0, 1, 2, 4)), plan(current = 3, available = 40))
        assertEquals(Plan(emptyList(), listOf(0, 1, 2, 3, 4)), plan(current = -1, available = 40))
    }
}
