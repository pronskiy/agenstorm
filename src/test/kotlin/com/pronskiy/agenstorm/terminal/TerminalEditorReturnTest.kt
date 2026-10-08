package com.pronskiy.agenstorm.terminal

import com.pronskiy.agenstorm.terminal.TerminalEditorReturn.Focus
import com.pronskiy.agenstorm.terminal.TerminalEditorReturn.Trigger
import com.pronskiy.agenstorm.terminal.TerminalMaximizeToggleAction.TerminalWindowState
import junit.framework.TestCase

/** Step J1.12: which file opens give the editor back from a maximized terminal — yours, not an agent's in the background. */
class TerminalEditorReturnTest : TestCase() {

    private val maximized = TerminalWindowState(visible = true, maximized = true, docked = true)
    private val split = TerminalWindowState(visible = true, maximized = false, docked = true)
    private val hidden = TerminalWindowState(visible = false, maximized = false, docked = true)
    private val settled = TerminalEditorReturn.GRACE_MS

    private fun gives(trigger: Trigger, focus: Focus, terminal: TerminalWindowState = maximized, enabled: Boolean = true, sinceMaximize: Long = settled) =
        TerminalEditorReturn.givesEditorBack(terminal, enabled, trigger, focus, sinceMaximize)

    fun testAFileEditorTakingTheKeyboardGivesTheEditorBack() {
        // A path clicked in the terminal, `open`, `$EDITOR`, Navigate, Recent Files, a Project view double click.
        assertTrue(gives(Trigger.EDITOR_FOCUSED, Focus.EDITOR))
    }

    fun testASingleClickInTheProjectViewGivesTheEditorBack() {
        assertTrue("the keyboard is in the window, outside the terminal", gives(Trigger.SELECTION_CHANGED, Focus.ELSEWHERE_IN_WINDOW))
        assertTrue("or already in the editor", gives(Trigger.SELECTION_CHANGED, Focus.EDITOR))
    }

    fun testABackgroundOpenWhileYouTypeInTheTerminalDoesNot() {
        assertFalse(gives(Trigger.SELECTION_CHANGED, Focus.TERMINAL))
    }

    fun testABackgroundOpenWithTheIdeInTheBackgroundDoesNot() {
        assertFalse(gives(Trigger.SELECTION_CHANGED, Focus.NONE))
    }

    fun testTheToggleOwnMaximizingIsNotUndone() {
        // J1.10: the click on the active tab focuses the editor while it asks for the terminal.
        assertFalse(gives(Trigger.EDITOR_FOCUSED, Focus.EDITOR, sinceMaximize = settled - 1))
        assertTrue(gives(Trigger.EDITOR_FOCUSED, Focus.EDITOR, sinceMaximize = settled))
    }

    fun testNothingUnlessTheTerminalIsMaximizedAndTheFeatureOn() {
        for (terminal in listOf(split, hidden)) {
            for (trigger in Trigger.entries) assertFalse("$terminal $trigger", gives(trigger, Focus.EDITOR, terminal = terminal))
        }
        for (trigger in Trigger.entries) assertFalse(gives(trigger, Focus.EDITOR, enabled = false))
    }
}
