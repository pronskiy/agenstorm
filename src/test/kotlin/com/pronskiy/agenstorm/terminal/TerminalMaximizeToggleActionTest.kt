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

    /** Kept in step with `plugin.xml` by the keymap tests below. */
    private val MAC_KEYSTROKE = "meta alt M"
    private val DEFAULT_KEYSTROKE = "alt shift F12"

    private val BINDINGS = listOf(
        KeymapManager.DEFAULT_IDEA_KEYMAP to DEFAULT_KEYSTROKE,
        KeymapManager.MAC_OS_X_10_5_PLUS_KEYMAP to MAC_KEYSTROKE,
        KeymapManager.MAC_OS_X_KEYMAP to MAC_KEYSTROKE,
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
    fun testEachKeymapGetsTheBindingPluginXmlPromises() {
        for ((name, stroke) in BINDINGS) {
            val keymap = KeymapManager.getInstance().getKeymap(name) ?: continue
            val claimed = keymap.getActionIds(KeyStroke.getKeyStroke(stroke)).toList()
            assertTrue("$stroke in the $name keymap is $claimed", actionId in claimed)
        }
    }

    /**
     * The macOS keystroke is shared, and that is a decision (46) rather than an oversight: ⌘⌥M is Extract
     * Method, `TerminalMaximizeShortcutPromoter` decides who wins it, and this case fails the day the
     * platform stops sharing it — at which point the promoter is dead weight and can go.
     *
     * The keymap is asked rather than searched, because a macOS keymap inherits `$default` with Ctrl and
     * Meta swapped (`MacOSDefaultKeymapKt.mapModifiers`): `control alt M` *is* ⌘⌥M there, and no keymap file
     * contains the string. A text search over the keymaps is what first called this keystroke free.
     */
    fun testTheMacKeystrokeIsTheOneTheRefactoringWants() {
        val keymap = KeymapManager.getInstance().getKeymap(KeymapManager.MAC_OS_X_10_5_PLUS_KEYMAP) ?: return

        val claimed = keymap.getActionIds(KeyStroke.getKeyStroke(MAC_KEYSTROKE)).toList()

        assertTrue("$MAC_KEYSTROKE is claimed by $claimed", actionId in claimed)
        assertTrue("the promoter exists for this: $claimed", "ExtractMethod" in claimed)
    }

    fun testTheDefaultKeystrokeIsClaimedByNothingElse() {
        val keymap = KeymapManager.getInstance().getKeymap(KeymapManager.DEFAULT_IDEA_KEYMAP) ?: return

        val claimed = keymap.getActionIds(KeyStroke.getKeyStroke(DEFAULT_KEYSTROKE)).toList()

        assertEquals("$DEFAULT_KEYSTROKE in ${KeymapManager.DEFAULT_IDEA_KEYMAP}", listOf(actionId), claimed)
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
