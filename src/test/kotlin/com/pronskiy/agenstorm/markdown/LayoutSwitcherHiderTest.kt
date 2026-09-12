package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.LayoutActionsFloatingToolbar
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JLayeredPane
import javax.swing.JPanel

/**
 * Step M1.1. The two Swing moves the feature is made of: find the floating layout toolbar somewhere under a
 * `TextEditorWithPreview`'s component, and take it out of the tree in a way that can be undone. The real toolbar is
 * used rather than a stand-in, because [LayoutSwitcherHider.findLayoutToolbar] matches on exactly that class — a
 * stand-in would keep passing on a build where the platform stopped using it.
 */
class LayoutSwitcherHiderTest : BasePlatformTestCase() {

    private fun toolbar() = LayoutActionsFloatingToolbar(JPanel(), DefaultActionGroup(), testRootDisposable)

    fun testFindsNothingInATreeWithoutALayoutToolbar() {
        val root = JPanel().apply { add(JPanel().apply { add(JPanel()) }) }

        assertNull(LayoutSwitcherHider.findLayoutToolbar(root))
    }

    fun testFindsTheToolbarNestedBelowTheRoot() {
        val toolbar = toolbar()
        val root = JPanel().apply { add(JPanel().apply { add(JLayeredPane().apply { add(toolbar) }) }) }

        assertSame(toolbar, LayoutSwitcherHider.findLayoutToolbar(root))
    }

    fun testFindsTheToolbarWhenItIsTheRoot() {
        val toolbar = toolbar()

        assertSame(toolbar, LayoutSwitcherHider.findLayoutToolbar(toolbar))
    }

    /**
     * The layer is asserted because it is easy to lose: from Kotlin `add(component, POPUP_LAYER)` binds to
     * `Container.add(Component, int)` and the layer arrives as a child index, leaving the toolbar under the splitter.
     */
    fun testDetachTakesTheToolbarOutAndReattachPutsItBackOnItsLayer() {
        val toolbar = toolbar()
        val pane = JLayeredPane()
        JLayeredPane.putLayer(toolbar, JLayeredPane.POPUP_LAYER)
        pane.add(toolbar)
        val root = JPanel().apply { add(pane) }

        val detached = LayoutSwitcherHider.detachFrom(root)
        assertNotNull(detached)
        assertSame(toolbar, detached!!.toolbar)
        assertSame(pane, detached.parent)
        assertEquals(JLayeredPane.POPUP_LAYER.toInt(), detached.layer)
        assertNull(toolbar.parent)
        assertNull(LayoutSwitcherHider.findLayoutToolbar(root))

        LayoutSwitcherHider.reattach(detached)
        assertSame(pane, toolbar.parent)
        assertSame(toolbar, LayoutSwitcherHider.findLayoutToolbar(root))
        assertEquals(JLayeredPane.POPUP_LAYER.toInt(), JLayeredPane.getLayer(toolbar))
    }

    fun testDetachIsANoOpWhenThereIsNoToolbar() {
        assertNull(LayoutSwitcherHider.detachFrom(JPanel().apply { add(JPanel()) }))
    }
}
