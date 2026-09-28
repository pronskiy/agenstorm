package com.pronskiy.agenstorm.terminal

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Step J1.11: what survives a close, and when a reopened terminal is ready to be maximized again. */
class TerminalMaximizeRestoreTest : BasePlatformTestCase() {

    fun testTheRecordIsReadOnce() {
        val properties = PropertiesComponent.getInstance(project)
        properties.setValue(TerminalMaximizeRestore.MAXIMIZED_KEY, true)
        properties.setValue(TerminalMaximizeRestore.HEIGHT_SHARE_KEY, "0.33")

        assertEquals(TerminalMaximizeRestore.Record(true, 0.33f), TerminalMaximizeRestore.takeRecord(project))
        assertEquals(TerminalMaximizeRestore.Record(false, null), TerminalMaximizeRestore.takeRecord(project))
    }

    fun testNothingRecordedMeansNotMaximized() {
        assertEquals(TerminalMaximizeRestore.Record(false, null), TerminalMaximizeRestore.takeRecord(project))
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
        // Saved stretched to 900 of 1000; the user's own height was a third.
        assertEquals(-570, TerminalMaximizeRestore.stretchBy(0.33f, total = 1000, current = 900))
        assertEquals(0, TerminalMaximizeRestore.stretchBy(0.5f, total = 600, current = 300))
        assertEquals(100, TerminalMaximizeRestore.stretchBy(0.5f, total = 600, current = 200))
    }

    /** No Terminal tool window, nothing to un-maximize and nothing written. */
    fun testClosingWithoutATerminalRecordsNothing() {
        TerminalMaximizeRestore.beforeSave(project)

        val properties = PropertiesComponent.getInstance(project)
        assertNull(properties.getValue(TerminalMaximizeRestore.MAXIMIZED_KEY))
        assertNull(properties.getValue(TerminalMaximizeRestore.HEIGHT_SHARE_KEY))
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
}
