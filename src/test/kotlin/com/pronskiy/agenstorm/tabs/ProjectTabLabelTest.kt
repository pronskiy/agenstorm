package com.pronskiy.agenstorm.tabs

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.tabs.ui.ProjectTabLabel
import java.awt.Component
import java.awt.event.MouseEvent

/** Phase E1 guardrail feedback: the × must not change the tab width; active tab shows it always, others on hover. */
class ProjectTabLabelTest : BasePlatformTestCase() {

    fun testCloseIconFollowsHoverAndSelectionWithoutChangingTheWidth() {
        val label = ProjectTabLabel(project, selected = false, onSelect = {}, onClose = {})
        val width = label.preferredSize.width
        assertFalse(label.isCloseShown)

        mouse(label, MouseEvent.MOUSE_ENTERED, 5, 5)
        assertTrue(label.isCloseShown)
        assertEquals(width, label.preferredSize.width)

        mouse(label, MouseEvent.MOUSE_EXITED, -1, -1)
        assertFalse(label.isCloseShown)
        assertEquals(width, label.preferredSize.width)

        label.isSelected = true
        assertTrue("the active tab always shows its ×", label.isCloseShown)
        assertEquals(width, label.preferredSize.width)

        mouse(label, MouseEvent.MOUSE_EXITED, -1, -1)
        assertTrue(label.isCloseShown)
    }

    fun testAHiddenCloseIconDoesNotCloseOnClick() {
        var closed = 0
        val label = ProjectTabLabel(project, selected = false, onSelect = {}, onClose = { closed++ })
        click(label.closeLabel)
        assertEquals(0, closed)

        mouse(label, MouseEvent.MOUSE_ENTERED, 5, 5)
        click(label.closeLabel)
        assertEquals(1, closed)
    }

    fun testSettingsControlTheIconAndTheMaximumWidth() {
        val long = FakeProjectHolder.another(project, "a-project-with-a-very-long-name-that-needs-an-ellipsis-for-sure")
        val wide = ProjectTabLabel(long, selected = false, onSelect = {}, onClose = {}, maxWidth = 600)
        val narrow = ProjectTabLabel(long, selected = false, onSelect = {}, onClose = {}, maxWidth = 100)
        assertTrue(wide.preferredSize.width > narrow.preferredSize.width)
        assertEquals(com.intellij.util.ui.JBUI.scale(100), narrow.preferredSize.width)

        val noIcon = ProjectTabLabel(project, selected = false, onSelect = {}, onClose = {}, showIcon = false)
        val withIcon = ProjectTabLabel(project, selected = false, onSelect = {}, onClose = {}, showIcon = true)
        assertTrue(noIcon.preferredSize.width <= withIcon.preferredSize.width)
    }

    private fun mouse(target: Component, id: Int, x: Int, y: Int) {
        target.dispatchEvent(MouseEvent(target, id, System.currentTimeMillis(), 0, x, y, 0, false))
    }

    private fun click(target: Component) {
        target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 2, 2, 1, false, MouseEvent.BUTTON1))
    }
}
