package com.pronskiy.agenstorm.tabs.git

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.intellij.openapi.wm.CustomStatusBarWidget
import git4idea.ui.branch.GitBranchWidget

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

    fun testStripeEdgeIsMeasuredFromAStripeButtonAtTheLeftEdge() {
        val root = javax.swing.JPanel(null)
        root.setBounds(0, 0, 800, 600)
        val stripe = javax.swing.JPanel(null).apply { setBounds(0, 0, 40, 600) }
        stripe.add(FakeStripeButton().apply { setBounds(4, 10, 32, 32) })
        root.add(stripe)
        val elsewhere = javax.swing.JPanel(null).apply { setBounds(300, 0, 40, 600) }
        elsewhere.add(FakeStripeButton().apply { setBounds(4, 10, 32, 32) })
        root.add(elsewhere)

        assertEquals(40, BranchStatusBarWidget.toolWindowStripeRightEdge(root))

        stripe.getComponent(0).isVisible = false
        assertNull("only visible buttons count, and the right-hand one is not at the edge", BranchStatusBarWidget.toolWindowStripeRightEdge(root))
        assertNull(BranchStatusBarWidget.toolWindowStripeRightEdge(javax.swing.JPanel()))
    }

    private class FakeStripeButton : javax.swing.JPanel()

    fun testWidgetWithoutRepositoriesHidesItsComponent() {
        val widget = BranchStatusBarWidget(project)
        Disposer.register(testRootDisposable, widget)
        assertNull(widget.repository())
        widget.refresh()
        assertFalse(widget.component.isVisible)
        assertEquals(BranchStatusBarWidgetFactory.ID, widget.ID())
    }

    /**
     * The widget is the Git plugin's own, so the branches popup and the branch icon come from public
     * `protected` members instead of the internal popup class. Its id stays ours, and so does a copy of it —
     * the platform makes one per frame, and the stock widget under our id would be a different thing.
     */
    fun testWidgetIsTheGitOneButKeepsOurIdentity() {
        val widget = BranchStatusBarWidget(project)
        Disposer.register(testRootDisposable, widget)

        assertEquals(GitBranchWidget::class.java, BranchStatusBarWidget::class.java.superclass)
        assertTrue(CustomStatusBarWidget::class.java.isInstance(widget))
        assertEquals(BranchStatusBarWidgetFactory.ID, widget.ID())

        val copy = widget.copy()
        Disposer.register(testRootDisposable, copy as BranchStatusBarWidget)
        assertEquals(BranchStatusBarWidgetFactory.ID, copy.ID())
    }
}
