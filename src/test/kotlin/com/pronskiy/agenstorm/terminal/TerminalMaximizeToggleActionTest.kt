package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.keymap.KeymapManager
import javax.swing.KeyStroke
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

    /** The one binding, kept in step with `plugin.xml` by the two keymap tests below. */
    private val KEYSTROKE = "alt shift F12"

    /** Every keymap that decides what this keystroke does on a machine someone actually uses. */
    private val KEYMAPS = listOf(
        KeymapManager.DEFAULT_IDEA_KEYMAP,
        KeymapManager.MAC_OS_X_10_5_PLUS_KEYMAP,
        KeymapManager.MAC_OS_X_KEYMAP,
        "macOS System Shortcuts",
    )

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

    /**
     * The binding has to be free in the keymap that will actually be in force, and "free" is not something
     * a text search can answer: macOS keymaps inherit `$default` with Ctrl and Meta swapped
     * (`MacOSDefaultKeymapKt.mapModifiers`), so `control alt M` — Extract Method — *is* ⌘⌥M there without
     * the string ever appearing in a keymap file. A text search over the keymaps is what first picked ⌘⌥M
     * for this action. Asking the keymap itself is the only check that cannot miss it.
     */
    fun testNothingElseClaimsTheBinding() {
        for (name in KEYMAPS) {
            val keymap = KeymapManager.getInstance().getKeymap(name) ?: continue
            val claimed = keymap.getActionIds(KeyStroke.getKeyStroke(KEYSTROKE)).toList()
            assertEquals("$KEYSTROKE in the $name keymap", listOf(actionId), claimed)
        }
    }

    fun testTheBindingReachesEveryKeymapWithoutAnOverride() {
        for (name in KEYMAPS - "macOS System Shortcuts") {
            val keymap = KeymapManager.getInstance().getKeymap(name) ?: continue
            val strokes = keymap.getShortcuts(actionId).map { it.toString() }
            assertTrue("$actionId has no binding in the $name keymap: $strokes", strokes.isNotEmpty())
        }
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
