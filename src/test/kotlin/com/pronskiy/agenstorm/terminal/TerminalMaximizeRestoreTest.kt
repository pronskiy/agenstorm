package com.pronskiy.agenstorm.terminal

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Step J1.11: what survives a close, the terminal's own height, and when a reopened terminal is ready. */
class TerminalMaximizeRestoreTest : BasePlatformTestCase() {

    fun testTheFlagIsReadOnce() {
        PropertiesComponent.getInstance(project).setValue(TerminalMaximizeRestore.MAXIMIZED_KEY, true)

        assertTrue(TerminalMaximizeRestore.takeMaximizedOnClose(project))
        assertFalse(TerminalMaximizeRestore.takeMaximizedOnClose(project))
    }

    fun testNothingRecordedMeansNotMaximized() {
        assertFalse(TerminalMaximizeRestore.takeMaximizedOnClose(project))
    }

    /** No Terminal tool window, nothing to un-maximize and nothing written. */
    fun testClosingWithoutATerminalRecordsNothing() {
        TerminalMaximizeRestore.beforeSave(project)

        val properties = PropertiesComponent.getInstance(project)
        assertNull(properties.getValue(TerminalMaximizeRestore.MAXIMIZED_KEY))
        assertNull(properties.getValue(TerminalMaximizeRestore.TERMINAL_HEIGHT_KEY))
        assertNull(properties.getValue(TerminalMaximizeRestore.SHARED_HEIGHT_KEY))
    }

    fun testOnlyAShareOfSomethingIsReadBack() {
        assertEquals(0.33f, TerminalMaximizeRestore.parseShare("0.33"))
        assertNull(TerminalMaximizeRestore.parseShare(null))
        assertNull(TerminalMaximizeRestore.parseShare("tall"))
        assertNull(TerminalMaximizeRestore.parseShare("0"))
        assertNull(TerminalMaximizeRestore.parseShare("1.0"))
        assertNull(TerminalMaximizeRestore.parseShare("NaN"))
    }

    fun testStretchesByTheDifferenceToTheShare() {
        // Shown stretched to 900 of 1000; the user's own height was a third.
        assertEquals(-570, TerminalMaximizeRestore.stretchBy(0.33f, total = 1000, current = 900))
        assertEquals(0, TerminalMaximizeRestore.stretchBy(0.5f, total = 600, current = 300))
        assertEquals(100, TerminalMaximizeRestore.stretchBy(0.5f, total = 600, current = 200))
    }

    fun testSettledOnlyOnScreenAtAHeightThatStoppedChanging() {
        assertTrue(TerminalMaximizeRestore.isSettled(visible = true, showing = true, height = 300, previousHeight = 300))
        // Still growing with the frame.
        assertFalse(TerminalMaximizeRestore.isSettled(visible = true, showing = true, height = 300, previousHeight = 120))
        // The first look has nothing to compare with.
        assertFalse(TerminalMaximizeRestore.isSettled(visible = true, showing = true, height = 300, previousHeight = -1))
        assertFalse(TerminalMaximizeRestore.isSettled(visible = true, showing = true, height = 0, previousHeight = 0))
        assertFalse(TerminalMaximizeRestore.isSettled(visible = true, showing = false, height = 300, previousHeight = 300))
        assertFalse(TerminalMaximizeRestore.isSettled(visible = false, showing = true, height = 300, previousHeight = 300))
    }

    fun testTheHeightUnmaximizingGaveBackIsTheShare() {
        assertEquals(0.25f, TerminalMaximizeRestore.ownShare(restored = 200, total = 800, maximized = 780))
    }

    /** A layout stretched by an earlier version: un-maximizing gives back what maximizing recorded, the full height. */
    fun testAlmostTheFullHeightBackMeansItWasLost() {
        assertEquals(1f / 3, TerminalMaximizeRestore.ownShare(restored = 760, total = 800, maximized = 780))
        assertEquals(1f / 3, TerminalMaximizeRestore.ownShare(restored = 780, total = 800, maximized = 780))
    }

    fun testNoMaximizedHeightToCompareWithTakesWhatCameBack() {
        assertEquals(0.95f, TerminalMaximizeRestore.ownShare(restored = 760, total = 800, maximized = 0))
    }

    fun testTheTerminalAlwaysTakesItsHeightBack() {
        assertTrue(TerminalMaximizeRestore.receivesOwnHeight(isTerminal = true, sizesShared = false, sameSide = true, docked = true))
    }

    /** The shared bottom height was stretched too, so the next bottom tool window shown puts it right. */
    fun testAnotherToolWindowAtItsSideTakesItWhileSizesAreShared() {
        assertTrue(TerminalMaximizeRestore.receivesOwnHeight(isTerminal = false, sizesShared = true, sameSide = true, docked = true))
    }

    fun testNotWhenEachToolWindowRemembersItsOwnSize() {
        assertFalse(TerminalMaximizeRestore.receivesOwnHeight(isTerminal = false, sizesShared = false, sameSide = true, docked = true))
    }

    fun testNotForAnotherSideOrAFloatingWindow() {
        assertFalse(TerminalMaximizeRestore.receivesOwnHeight(isTerminal = false, sizesShared = true, sameSide = false, docked = true))
        assertFalse(TerminalMaximizeRestore.receivesOwnHeight(isTerminal = false, sizesShared = true, sameSide = true, docked = false))
    }
}
