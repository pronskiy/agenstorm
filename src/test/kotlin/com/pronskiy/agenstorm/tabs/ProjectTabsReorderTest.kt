package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.tabs.ui.ProjectTabLabel
import com.pronskiy.agenstorm.tabs.ui.ProjectTabsPanel
import java.awt.event.MouseEvent

/** Step E2.2: dragging a tab past its neighbours reports the new index; a nudge does not. */
class ProjectTabsReorderTest : BasePlatformTestCase() {

    private lateinit var panel: ProjectTabsPanel
    private lateinit var tabs: List<Project>
    private val moves = mutableListOf<Pair<Project, Int>>()

    override fun setUp() {
        super.setUp()
        tabs = listOf(project, FakeProjectHolder.another(project, "beta"), FakeProjectHolder.another(project, "gamma"))
        panel = ProjectTabsPanel(ProjectTabsModel.getInstance())
        panel.showTabs(tabs)
        panel.availableWidthProvider = { 100_000 }
        panel.size = panel.preferredSize
        panel.doLayout()
        panel.onReorder = { p, i -> moves += p to i }
    }

    private fun label(index: Int): ProjectTabLabel = panel.tabLabels()[index]

    private fun mouse(target: ProjectTabLabel, id: Int, panelX: Int, button: Int = MouseEvent.BUTTON1) {
        val x = panelX - target.x
        val modifiers = if (id == MouseEvent.MOUSE_DRAGGED) MouseEvent.BUTTON1_DOWN_MASK else 0
        target.dispatchEvent(MouseEvent(target, id, System.currentTimeMillis(), modifiers, x, 5, 1, false, button))
    }

    fun testDraggingTheFirstTabPastTheLastOneMovesItToTheEnd() {
        val first = label(0)
        val last = label(2)
        val beyondLast = last.x + last.width - 2

        mouse(first, MouseEvent.MOUSE_PRESSED, first.x + 5)
        mouse(first, MouseEvent.MOUSE_DRAGGED, first.x + first.width - 2)
        assertEquals("marker after the first tab while over its right half", 1, panel.dropIndex)
        mouse(first, MouseEvent.MOUSE_DRAGGED, beyondLast)
        assertEquals(3, panel.dropIndex)
        mouse(first, MouseEvent.MOUSE_RELEASED, beyondLast)

        assertEquals(listOf(project to 2), moves)
        assertNull(panel.dropIndex)
    }

    fun testDraggingTheLastTabBeforeTheFirstMovesItToTheFront() {
        val last = label(2)
        mouse(last, MouseEvent.MOUSE_PRESSED, last.x + 5)
        mouse(last, MouseEvent.MOUSE_DRAGGED, 2)
        mouse(last, MouseEvent.MOUSE_RELEASED, 2)

        assertEquals(listOf(tabs[2] to 0), moves)
    }

    fun testANudgeOrADropOnTheOwnPositionChangesNothing() {
        val middle = label(1)
        mouse(middle, MouseEvent.MOUSE_PRESSED, middle.x + 10)
        mouse(middle, MouseEvent.MOUSE_DRAGGED, middle.x + 12)
        mouse(middle, MouseEvent.MOUSE_RELEASED, middle.x + 12)
        assertTrue(moves.isEmpty())

        mouse(middle, MouseEvent.MOUSE_PRESSED, middle.x + 10)
        mouse(middle, MouseEvent.MOUSE_DRAGGED, middle.x + 30)
        mouse(middle, MouseEvent.MOUSE_RELEASED, middle.x + 30)
        assertTrue("dropping a tab onto itself is not a move", moves.isEmpty())
    }

    fun testInsertionIndexFollowsTabCentres() {
        assertEquals(0, panel.insertionIndex(0))
        assertEquals(1, panel.insertionIndex(label(0).x + label(0).width - 1))
        assertEquals(3, panel.insertionIndex(label(2).x + label(2).width))
    }
}
