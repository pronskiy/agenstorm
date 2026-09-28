package com.pronskiy.agenstorm.terminal

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Step J1.11: what survives a close, and when a reopened terminal is ready to be maximized again. */
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

        assertNull(PropertiesComponent.getInstance(project).getValue(TerminalMaximizeRestore.MAXIMIZED_KEY))
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
