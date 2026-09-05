package com.pronskiy.agenstorm.tabs.git

import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormSettings

/** Step E3.2: the factory is registered, needs Git repositories and the feature, and the text rules. */
class BranchStatusBarWidgetTest : BasePlatformTestCase() {

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

    fun testFactoryIsRegisteredAndUnavailableWithoutRepositories() {
        val factory = StatusBarWidgetFactory.EP_NAME.extensionList.single { it.id == BranchStatusBarWidgetFactory.ID }
        assertTrue(factory is BranchStatusBarWidgetFactory)
        assertTrue(factory.isEnabledByDefault)
        assertFalse("the test project has no Git repository", factory.isAvailable(project))
    }

    fun testFeatureToggleGatesTheWidget() {
        assertTrue(BranchStatusBarWidgetFactory.isFeatureOn())
        AgenstormSettings.getInstance().state.branchInStatusBar = false
        assertFalse(BranchStatusBarWidgetFactory.isFeatureOn())
        AgenstormSettings.getInstance().state.branchInStatusBar = true
        AgenstormSettings.getInstance().state.projectTabsEnabled = false
        assertFalse(BranchStatusBarWidgetFactory.isFeatureOn())
    }

    fun testTextPrefersTheBranchThenAShortRevisionThenAPlaceholder() {
        assertEquals("main", BranchStatusBarWidget.textFor("main", "0123456789abcdef"))
        assertEquals("01234567", BranchStatusBarWidget.textFor(null, "0123456789abcdef"))
        assertEquals("01234567", BranchStatusBarWidget.textFor("", "0123456789abcdef"))
        assertEquals(AgenstormBundle.message("tabs.branch.noBranch"), BranchStatusBarWidget.textFor(null, null))
    }

    fun testAlignmentPaddingMovesTheIconToTheStripeEdge() {
        assertEquals(26, BranchStatusBarWidget.alignmentPadding(stripeRight = 40, labelX = 14))
        assertEquals(0, BranchStatusBarWidget.alignmentPadding(stripeRight = 10, labelX = 30))
        assertEquals(com.intellij.util.ui.JBUI.scale(80), BranchStatusBarWidget.alignmentPadding(stripeRight = 500, labelX = 0))
    }

    fun testWidgetWithoutRepositoriesHidesItsComponent() {
        val widget = BranchStatusBarWidget(project)
        assertNull(widget.repository())
        widget.refresh()
        assertFalse(widget.component.isVisible)
        assertEquals(BranchStatusBarWidgetFactory.ID, widget.ID())
    }
}
