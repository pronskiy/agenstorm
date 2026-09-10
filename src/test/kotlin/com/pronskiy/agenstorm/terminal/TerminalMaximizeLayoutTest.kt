package com.pronskiy.agenstorm.terminal

import com.intellij.ide.ui.UISettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.terminal.TerminalMaximizeLayout.WideScreenChange

/**
 * Step J2.1: the terminal fills the editor's column rather than the window, which needs the widescreen tool
 * window layout. Agenstorm turns it on once and only ever turns back what it turned on.
 */
class TerminalMaximizeLayoutTest : BasePlatformTestCase() {

    private var wideScreenBefore: Boolean = false

    override fun setUp() {
        super.setUp()
        wideScreenBefore = UISettings.getInstance().wideScreenSupport
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
    }

    override fun tearDown() {
        try {
            UISettings.getInstance().wideScreenSupport = wideScreenBefore
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private fun ui(wideScreen: Boolean): UISettings =
        UISettings.getInstance().also { it.wideScreenSupport = wideScreen }

    fun testTurnsTheLayoutOnAndRecordsThatItWasUs() {
        val state = AgenstormSettings.State()
        val ui = ui(wideScreen = false)

        val change = TerminalMaximizeLayout.syncWideScreen(featureOn = true, state = state, ui = ui)

        assertEquals(WideScreenChange.ENABLED, change)
        assertTrue(ui.wideScreenSupport)
        assertTrue(state.terminalMaximizeWideScreenByAgenstorm)
    }

    fun testALayoutTheUserAlreadyChoseIsNeverClaimed() {
        val state = AgenstormSettings.State()
        val ui = ui(wideScreen = true)

        val change = TerminalMaximizeLayout.syncWideScreen(featureOn = true, state = state, ui = ui)

        assertEquals(WideScreenChange.NONE, change)
        assertTrue(ui.wideScreenSupport)
        assertFalse("we did not turn it on, so we must not offer to turn it off", state.terminalMaximizeWideScreenByAgenstorm)
    }

    fun testSwitchingTheFeatureOffPutsBackOnlyWhatWeTurnedOn() {
        val state = AgenstormSettings.State(terminalMaximizeWideScreenByAgenstorm = true)
        val ui = ui(wideScreen = true)

        val change = TerminalMaximizeLayout.syncWideScreen(featureOn = false, state = state, ui = ui)

        assertEquals(WideScreenChange.RESTORED, change)
        assertFalse(ui.wideScreenSupport)
        assertFalse(state.terminalMaximizeWideScreenByAgenstorm)
    }

    fun testSwitchingTheFeatureOffLeavesTheUsersOwnLayoutAlone() {
        val state = AgenstormSettings.State()
        val ui = ui(wideScreen = true)

        val change = TerminalMaximizeLayout.syncWideScreen(featureOn = false, state = state, ui = ui)

        assertEquals(WideScreenChange.NONE, change)
        assertTrue("the user's own widescreen layout must survive", ui.wideScreenSupport)
    }

    /** The user turned it off again by hand; switching the feature off must not fight them. */
    fun testAUserWhoTurnsItOffThemselvesIsNotOverridden() {
        val state = AgenstormSettings.State(terminalMaximizeWideScreenByAgenstorm = true)
        val ui = ui(wideScreen = false)

        val change = TerminalMaximizeLayout.syncWideScreen(featureOn = false, state = state, ui = ui)

        assertEquals(WideScreenChange.NONE, change)
        assertFalse(ui.wideScreenSupport)
        assertFalse("the claim is dropped either way", state.terminalMaximizeWideScreenByAgenstorm)
    }

    fun testTurningItOnIsIdempotent() {
        val state = AgenstormSettings.State()
        val ui = ui(wideScreen = false)

        TerminalMaximizeLayout.syncWideScreen(featureOn = true, state = state, ui = ui)
        val second = TerminalMaximizeLayout.syncWideScreen(featureOn = true, state = state, ui = ui)

        assertEquals(WideScreenChange.NONE, second)
        assertTrue(state.terminalMaximizeWideScreenByAgenstorm)
    }
}
