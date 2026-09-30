package com.pronskiy.agenstorm.worktrees

import com.pronskiy.agenstorm.worktrees.SwitchPolicy.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Step T1.6: a worktree switch swaps the window in place and never closes anything busy. */
class SwitchPolicyTest {

    private val main = "/r"
    private val a = "/r/.worktrees/a"
    private val b = "/r/.worktrees/b"
    private val c = "/r/.worktrees/c"

    @Test
    fun theWorktreeAlreadyShownIsNoSwitch() {
        assertNull(SwitchPolicy.decide(main, main, loaded = setOf(main), busy = emptySet(), leftBehind = emptySet()))
    }

    @Test
    fun anIdleWorktreeIsSwappedOutForOneThatIsNotOpenYet() {
        assertEquals(
            Plan(a, open = true, closeCurrent = true, closeLeftBehind = emptyList(), leftBehind = emptySet()),
            SwitchPolicy.decide(a, main, loaded = setOf(main), busy = emptySet(), leftBehind = emptySet()),
        )
    }

    @Test
    fun aBusyWorktreeStaysOpenBehindAndIsRemembered() {
        assertEquals(
            Plan(a, open = true, closeCurrent = false, closeLeftBehind = emptyList(), leftBehind = setOf(main)),
            SwitchPolicy.decide(a, main, loaded = setOf(main), busy = setOf(main), leftBehind = emptySet()),
        )
    }

    @Test
    fun switchingBackToAWorktreeLeftBehindFocusesItAndClosesTheIdleCurrentOne() {
        assertEquals(
            Plan(main, open = false, closeCurrent = true, closeLeftBehind = emptyList(), leftBehind = emptySet()),
            SwitchPolicy.decide(main, a, loaded = setOf(main, a), busy = setOf(main), leftBehind = setOf(main)),
        )
    }

    @Test
    fun aWorktreeLeftBehindIsClosedOnTheNextSwitchOnceItIsIdle() {
        assertEquals(
            Plan(b, open = true, closeCurrent = true, closeLeftBehind = listOf(main), leftBehind = emptySet()),
            SwitchPolicy.decide(b, a, loaded = setOf(main, a), busy = emptySet(), leftBehind = setOf(main)),
        )
    }

    @Test
    fun aWorktreeLeftBehindThatIsStillBusyStaysAndStaysRemembered() {
        assertEquals(
            Plan(b, open = true, closeCurrent = true, closeLeftBehind = emptyList(), leftBehind = setOf(main)),
            SwitchPolicy.decide(b, a, loaded = setOf(main, a), busy = setOf(main), leftBehind = setOf(main)),
        )
    }

    @Test
    fun aWorktreeLeftBehindThatTheUserClosedIsForgotten() {
        assertEquals(
            Plan(b, open = true, closeCurrent = true, closeLeftBehind = emptyList(), leftBehind = emptySet()),
            SwitchPolicy.decide(b, a, loaded = setOf(a), busy = emptySet(), leftBehind = setOf(main)),
        )
    }

    @Test
    fun aWorktreeOpenedInItsOwnWindowOnPurposeIsNeverClosedByASwitch() {
        assertEquals(
            Plan(b, open = true, closeCurrent = true, closeLeftBehind = emptyList(), leftBehind = emptySet()),
            SwitchPolicy.decide(b, a, loaded = setOf(a, c), busy = emptySet(), leftBehind = emptySet()),
        )
    }

    @Test
    fun keepingTheCurrentOpenClosesNothingAndFocusesWhatIsOpen() {
        assertEquals(
            Plan(a, open = true, closeCurrent = false, closeLeftBehind = emptyList(), leftBehind = emptySet()),
            SwitchPolicy.decide(a, main, loaded = setOf(main), busy = emptySet(), leftBehind = emptySet(), keepCurrent = true),
        )
        assertEquals(
            Plan(main, open = false, closeCurrent = false, closeLeftBehind = emptyList(), leftBehind = setOf(b)),
            SwitchPolicy.decide(main, a, loaded = setOf(main, a, b), busy = emptySet(), leftBehind = setOf(b, main), keepCurrent = true),
        )
    }
}
