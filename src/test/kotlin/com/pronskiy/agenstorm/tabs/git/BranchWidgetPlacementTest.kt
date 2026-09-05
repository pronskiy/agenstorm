package com.pronskiy.agenstorm.tabs.git

import com.intellij.ide.ui.NavBarLocation
import com.intellij.ide.ui.UISettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.tabs.git.BranchWidgetPlacement.NavBarChange
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

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

    fun testLabelGoesToTheFrontOfTheStatusBarsWestBoxAndComesBackOut() {
        val bar = JPanel(BorderLayout())
        val right = JPanel().also { bar.add(it, BorderLayout.EAST) }
        val host = JPanel().also { right.add(it) }
        val label = JBLabel("main")

        val attachment = BranchWidgetPlacement.attach(bar, host, label)

        assertTrue(attachment.leftCorner)
        val west = (bar.layout as BorderLayout).getLayoutComponent(BorderLayout.WEST) as JComponent
        assertSame(west, label.parent)
        assertSame(attachment.ownWestPanel, west)
        assertFalse(host.isVisible)

        // A second attach is idempotent.
        assertSame(west, BranchWidgetPlacement.attach(bar, host, label).container)
        assertEquals(1, west.componentCount)

        BranchWidgetPlacement.detach(attachment)
        assertNull(label.parent)
        assertNull("our own empty box is removed again", (bar.layout as BorderLayout).getLayoutComponent(BorderLayout.WEST))
    }

    fun testAnExistingWestBoxIsReusedWithTheLabelFirst() {
        val bar = JPanel(BorderLayout())
        val platformWest = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS); add(JLabel("breadcrumbs")) }
        bar.add(platformWest, BorderLayout.WEST)
        val label = JBLabel("main")

        val attachment = BranchWidgetPlacement.attach(bar, JPanel(), label)

        assertSame(platformWest, label.parent)
        assertSame(label, platformWest.getComponent(0))
        assertNull(attachment.ownWestPanel)

        BranchWidgetPlacement.detach(attachment)
        assertEquals("the platform's own content stays", 1, platformWest.componentCount)
        assertNotNull(platformWest.parent)
    }

    fun testWithoutABorderLayoutTheLabelShowsInsideTheHost() {
        val host = JPanel()
        val label = JBLabel("main")
        val attachment = BranchWidgetPlacement.attach(JPanel(), host, label)
        assertFalse(attachment.leftCorner)
        assertSame(host, label.parent)
        assertTrue(host.isVisible)
    }
}
