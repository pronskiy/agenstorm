package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.terminal.TerminalMaximizeToggleAction.Step
import com.pronskiy.agenstorm.terminal.TerminalMaximizeToggleAction.TerminalWindowState
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory

/**
 * Steps J1.1–J1.3: the toggle is registered with its binding, hides itself when the feature is off or there is
 * no terminal, and decides what a press does from the tool window's current state.
 */
class TerminalMaximizeToggleActionTest : BasePlatformTestCase() {

    private val actionId = "Agenstorm.ToggleTerminalMaximized"

    override fun setUp() {
        super.setUp()
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
    }

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testActionIsRegisteredWithItsText() {
        val action = ActionManager.getInstance().getAction(actionId)

        assertTrue("$actionId is $action", action is TerminalMaximizeToggleAction)
        assertEquals("Maximize Terminal", action.templatePresentation.text)
        assertTrue(action.templatePresentation.description!!.isNotBlank())
    }

    /**
     * The one Agenstorm action that ships a binding — decision 40 narrows decision 12 for it, so unlike every
     * other action test this one asserts the shortcut list is *not* empty.
     */
    fun testActionShipsADefaultShortcut() {
        val shortcuts = KeymapManager.getInstance().activeKeymap.getShortcuts(actionId)

        assertTrue("expected a default binding for $actionId", shortcuts.isNotEmpty())
    }

    fun testTheToolWindowIdMatchesTheTerminalPlugin() {
        assertEquals(
            TerminalToolWindowFactory.TOOL_WINDOW_ID,
            TerminalMaximizeToggleAction.TERMINAL_TOOL_WINDOW_ID,
        )
    }

    fun testHiddenWhileTheFeatureIsOff() {
        val action = TerminalMaximizeToggleAction()
        AgenstormSettings.getInstance().state.terminalMaximizeEnabled = false

        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
        action.update(event)

        assertFalse(event.presentation.isEnabledAndVisible)
    }

    fun testHiddenWithoutAProject() {
        val action = TerminalMaximizeToggleAction()

        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.EMPTY_CONTEXT)
        action.update(event)

        assertFalse(event.presentation.isEnabledAndVisible)
        assertNull(TerminalMaximizeToggleAction.terminalOf(null))
    }

    fun testADockedTerminalTogglesBetweenTheTwoStates() {
        val hidden = TerminalWindowState(visible = false, maximized = false, docked = true)
        val split = TerminalWindowState(visible = true, maximized = false, docked = true)
        val maximized = TerminalWindowState(visible = true, maximized = true, docked = true)

        // The toggle asks for the state it is moving to; from anywhere but maximized, that is "maximize me".
        assertEquals(Step.MAXIMIZE_TERMINAL, TerminalMaximizeToggleAction.nextStep(hidden, wantMaximized = true))
        assertEquals(Step.MAXIMIZE_TERMINAL, TerminalMaximizeToggleAction.nextStep(split, wantMaximized = true))
        assertEquals(Step.MAXIMIZE_EDITOR, TerminalMaximizeToggleAction.nextStep(maximized, wantMaximized = false))
    }

    /** A floating or windowed terminal is its own window; maximizing that is not what the button promises. */
    fun testAFloatingTerminalIsOnlyActivated() {
        val floating = TerminalWindowState(visible = true, maximized = false, docked = false)

        assertEquals(Step.ACTIVATE_ONLY, TerminalMaximizeToggleAction.nextStep(floating, wantMaximized = true))
        assertEquals(Step.ACTIVATE_ONLY, TerminalMaximizeToggleAction.nextStep(floating, wantMaximized = false))
    }

    fun testOnlyADockedVisibleMaximizedTerminalCountsAsMaximized() {
        assertTrue(TerminalWindowState(visible = true, maximized = true, docked = true).isTerminalMaximized)
        assertFalse(TerminalWindowState(visible = false, maximized = true, docked = true).isTerminalMaximized)
        assertFalse(TerminalWindowState(visible = true, maximized = false, docked = true).isTerminalMaximized)
        assertFalse(TerminalWindowState(visible = true, maximized = true, docked = false).isTerminalMaximized)
    }
}
