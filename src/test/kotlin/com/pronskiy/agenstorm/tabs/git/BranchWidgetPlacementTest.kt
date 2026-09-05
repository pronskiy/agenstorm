package com.pronskiy.agenstorm.tabs.git

import com.intellij.ide.ui.NavBarLocation
import com.intellij.ide.ui.UISettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.tabs.git.BranchWidgetPlacement.NavBarChange

/** Step E3.3: the bottom navigation bar is hidden for the branch and restored only when we hid it. */
class BranchWidgetPlacementTest : BasePlatformTestCase() {

    private lateinit var ui: UISettings
    private var originalShow = true
    private lateinit var originalLocation: NavBarLocation

    override fun setUp() {
        super.setUp()
        ui = UISettings.getInstance()
        originalShow = ui.showNavigationBar
        originalLocation = ui.navBarLocation
    }

    override fun tearDown() {
        try {
            ui.showNavigationBar = originalShow
            ui.navBarLocation = originalLocation
        } finally {
            super.tearDown()
        }
    }

    fun testFeatureOnHidesABottomNavBarOnceAndRemembersIt() {
        ui.showNavigationBar = true
        ui.navBarLocation = NavBarLocation.BOTTOM
        val state = AgenstormSettings.State()

        assertEquals(NavBarChange.HIDDEN, BranchWidgetPlacement.syncNavBar(true, state, ui))
        assertFalse(ui.showNavigationBar)
        assertTrue(state.navBarHiddenByAgenstorm)
        assertEquals(NavBarChange.NONE, BranchWidgetPlacement.syncNavBar(true, state, ui))
    }

    fun testATopNavBarOrAHiddenOneIsLeftAlone() {
        ui.showNavigationBar = true
        ui.navBarLocation = NavBarLocation.TOP
        val state = AgenstormSettings.State()
        assertEquals(NavBarChange.NONE, BranchWidgetPlacement.syncNavBar(true, state, ui))
        assertTrue(ui.showNavigationBar)
        assertFalse(state.navBarHiddenByAgenstorm)

        ui.showNavigationBar = false
        ui.navBarLocation = NavBarLocation.BOTTOM
        assertEquals(NavBarChange.NONE, BranchWidgetPlacement.syncNavBar(true, state, ui))
        assertFalse("a user's own choice is not ours to restore later", state.navBarHiddenByAgenstorm)
    }

    fun testFeatureOffRestoresOnlyWhatWeHid() {
        ui.showNavigationBar = false
        ui.navBarLocation = NavBarLocation.BOTTOM
        val state = AgenstormSettings.State()

        assertEquals(NavBarChange.NONE, BranchWidgetPlacement.syncNavBar(false, state, ui))
        assertFalse(ui.showNavigationBar)

        state.navBarHiddenByAgenstorm = true
        assertEquals(NavBarChange.RESTORED, BranchWidgetPlacement.syncNavBar(false, state, ui))
        assertTrue(ui.showNavigationBar)
        assertFalse(state.navBarHiddenByAgenstorm)

        state.navBarHiddenByAgenstorm = true
        assertEquals("already visible again: just forget the flag", NavBarChange.NONE, BranchWidgetPlacement.syncNavBar(false, state, ui))
        assertFalse(state.navBarHiddenByAgenstorm)
    }

    fun testApplyNowOnAProjectWithoutGitDoesNotThrow() {
        BranchWidgetPlacement.applyNow(project)
    }
}
