package com.pronskiy.agenstorm.tabs

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.tabs.ui.ProjectTabLabel
import com.intellij.util.ui.NamedColorUtil
import java.awt.Component
import java.awt.event.MouseEvent

/** Phase E1 guardrail feedback and P2.7: the × must not change the tab width; active tab shows it always, others on hover; offloaded tabs are bookmarks. */
class ProjectTabLabelTest : BasePlatformTestCase() {

    fun testCloseIconFollowsHoverAndSelectionWithoutChangingTheWidth() {
        val label = ProjectTabLabel(ProjectTab.Loaded(project), selected = false, onSelect = {}, onClose = {})
        val width = label.preferredSize.width
        assertFalse(label.isCloseShown)

        label.hoverListener.mouseEntered(label, 5, 5)
        assertTrue(label.isCloseShown)
        assertEquals(width, label.preferredSize.width)

        label.hoverListener.mouseExited(label)
        assertFalse(label.isCloseShown)
        assertEquals(width, label.preferredSize.width)

        label.isSelected = true
        assertTrue("the active tab always shows its ×", label.isCloseShown)
        assertEquals(width, label.preferredSize.width)

        label.hoverListener.mouseExited(label)
        assertTrue(label.isCloseShown)
    }

    /**
     * The stuck-highlight bug: hover used to be tracked with AWT enter/exit events on the tab and its name, so a
     * pointer that left the tab through the × (which had no such listener) or that was still over the tab when its
     * window went behind never cleared it. The platform's hover service reports one exit for the tab as soon as
     * the pointer is no longer over it, whichever child it left through and whichever window it is in now.
     */
    fun testLeavingTheTabThroughTheCloseButtonClearsTheHover() {
        val label = ProjectTabLabel(ProjectTab.Loaded(project), selected = false, onSelect = {}, onClose = {})
        label.hoverListener.mouseEntered(label, 5, 5)
        assertTrue(label.isCloseShown)

        // Pointer moves onto the ×: AWT tells the tab the pointer left it, but it is still over the tab.
        mouse(label, MouseEvent.MOUSE_EXITED, label.width - 4, 5)
        mouse(label.closeLabel, MouseEvent.MOUSE_ENTERED, 2, 2)
        assertTrue("still hovered while over the ×", label.isCloseShown)

        // Pointer leaves the tab from the ×: the only AWT exit goes to the ×, the hover service exits the tab.
        mouse(label.closeLabel, MouseEvent.MOUSE_EXITED, 40, 2)
        label.hoverListener.mouseExited(label)
        assertFalse("hover is gone once the pointer left the tab", label.isCloseShown)
    }

    fun testAHiddenCloseIconDoesNotCloseOnClick() {
        var closed = 0
        val label = ProjectTabLabel(ProjectTab.Loaded(project), selected = false, onSelect = {}, onClose = { closed++ })
        click(label.closeLabel)
        assertEquals(0, closed)

        label.hoverListener.mouseEntered(label, 5, 5)
        click(label.closeLabel)
        assertEquals(1, closed)
    }

    fun testSettingsControlTheIconAndTheMaximumWidth() {
        val long = FakeProjectHolder.another(project, "a-project-with-a-very-long-name-that-needs-an-ellipsis-for-sure")
        val wide = ProjectTabLabel(ProjectTab.Loaded(long), selected = false, onSelect = {}, onClose = {}, maxWidth = 600)
        val narrow = ProjectTabLabel(ProjectTab.Loaded(long), selected = false, onSelect = {}, onClose = {}, maxWidth = 100)
        assertTrue(wide.preferredSize.width > narrow.preferredSize.width)
        assertEquals(com.intellij.util.ui.JBUI.scale(100), narrow.preferredSize.width)

        val noIcon = ProjectTabLabel(ProjectTab.Loaded(project), selected = false, onSelect = {}, onClose = {}, showIcon = false)
        val withIcon = ProjectTabLabel(ProjectTab.Loaded(project), selected = false, onSelect = {}, onClose = {}, showIcon = true)
        assertTrue(noIcon.preferredSize.width <= withIcon.preferredSize.width)
    }

    /** Step P2.7: an offloaded tab is a dimmed, dotted bookmark; a click means load, the × means forget. */
    fun testAnOffloadedTabIsMarkedAndItsClicksMeanLoadAndForget() {
        var selected = 0
        var closed = 0
        val tab = ProjectTab.Offloaded("/fake/beta", "beta", sinceMs = System.currentTimeMillis() - 7_200_000L)
        val label = ProjectTabLabel(tab, selected = false, onSelect = { selected++ }, onClose = { closed++ })

        assertTrue(label.isOffloaded)
        assertNull("no open project behind it", label.project)
        assertEquals("beta", label.title)
        assertTrue(label.toolTipText, "/fake/beta" in label.toolTipText)
        assertEquals(NamedColorUtil.getInactiveTextColor(), label.textColor())
        assertFalse(label.isCloseShown)

        click(label)
        assertEquals(1, selected)

        label.hoverListener.mouseEntered(label, 5, 5)
        assertTrue(label.isCloseShown)
        click(label.closeLabel)
        assertEquals(1, closed)
    }

    /** Guardrail feedback 2026-09-19: the dotted border was too loud; it is the inactive colour at reduced alpha now. */
    fun testTheBookmarkBorderIsAFadedInactiveColour() {
        val label = ProjectTabLabel(ProjectTab.Offloaded("/fake/beta", "beta", 0L), selected = false, onSelect = {}, onClose = {})
        val border = label.borderColor()
        val inactive = NamedColorUtil.getInactiveTextColor()
        assertEquals(inactive.rgb and 0xFFFFFF, border.rgb and 0xFFFFFF)
        assertTrue("alpha ${border.alpha} should be well below opaque", border.alpha in 60..140)
    }

    fun testALoadingTabKeepsItsMarkUntilTheStripIsRebuilt() {
        val tab = ProjectTab.Offloaded("/fake/beta", "beta", sinceMs = 0L)
        val label = ProjectTabLabel(tab, selected = false, onSelect = {}, onClose = {})
        assertFalse(label.isLoading)
        label.isLoading = true
        assertTrue(label.isLoading)
        assertTrue("still a bookmark while loading", label.isOffloaded)
    }

    private fun mouse(target: Component, id: Int, x: Int, y: Int) {
        target.dispatchEvent(MouseEvent(target, id, System.currentTimeMillis(), 0, x, y, 0, false))
    }

    private fun click(target: Component) {
        target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 2, 2, 1, false, MouseEvent.BUTTON1))
    }
}
