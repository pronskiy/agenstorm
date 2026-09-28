package com.pronskiy.agenstorm.terminal

import com.pronskiy.agenstorm.terminal.EditorTabClickWatcher.TabClick
import com.pronskiy.agenstorm.terminal.TerminalMaximizeToggleAction.TerminalWindowState
import junit.framework.TestCase

/** Steps J1.9 and J1.10: what a click on an editor tab asks for, given the terminal and how the editor came back. */
class EditorTabClickWatcherTest : TestCase() {

    private val maximized = TerminalWindowState(visible = true, maximized = true, docked = true)
    private val hidden = TerminalWindowState(visible = false, maximized = false, docked = true)
    private val split = TerminalWindowState(visible = true, maximized = false, docked = true)
    private val floatingHidden = TerminalWindowState(visible = false, maximized = false, docked = false)

    fun testAnyTabOverAMaximizedTerminalAsksForTheEditor() {
        for (armed in listOf(false, true)) {
            for (wasActive in listOf(false, true)) {
                assertEquals(TabClick.EDITOR, EditorTabClickWatcher.tabClick(maximized, armed, wasActive))
            }
        }
    }

    fun testTheActiveTabAsksForTheTerminalOnceATabClickGaveTheEditorBack() {
        assertEquals(TabClick.TERMINAL, EditorTabClickWatcher.tabClick(hidden, armed = true, wasActive = true))
    }

    fun testAnotherTabOnlySwitches() {
        assertEquals(TabClick.NONE, EditorTabClickWatcher.tabClick(hidden, armed = true, wasActive = false))
    }

    /** Leaving the terminal by the shortcut leaves the tab row alone. */
    fun testTheActiveTabDoesNothingUnlessATabClickGaveTheEditorBack() {
        assertEquals(TabClick.NONE, EditorTabClickWatcher.tabClick(hidden, armed = false, wasActive = true))
    }

    /** A terminal at its normal size is used beside the editor; the active tab is how the caret gets back to the file. */
    fun testTheActiveTabDoesNothingWhileTheTerminalIsShownBesideTheEditor() {
        assertEquals(TabClick.NONE, EditorTabClickWatcher.tabClick(split, armed = true, wasActive = true))
    }

    fun testTheActiveTabDoesNothingForAFloatingTerminal() {
        assertEquals(TabClick.NONE, EditorTabClickWatcher.tabClick(floatingHidden, armed = true, wasActive = true))
    }
}
