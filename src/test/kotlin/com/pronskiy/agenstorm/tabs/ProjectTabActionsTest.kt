package com.pronskiy.agenstorm.tabs

import com.intellij.ide.RecentProjectListActionProvider
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.tabs.ui.ProjectTabsPanel
import java.awt.Point
import java.awt.datatransfer.DataFlavor
import java.awt.event.MouseEvent

/** Step E1.4: the pure parts of the tab interactions (neighbour choice, popup contents, clipboard, right click). */
class ProjectTabActionsTest : BasePlatformTestCase() {

    fun testNeighbourIsTheNextTabThenThePreviousOneThenNothing() {
        val a = project
        val b = FakeProjectHolder.another(a, "b")
        val c = FakeProjectHolder.another(a, "c")
        val tabs = listOf(a, b, c)
        assertSame(c, ProjectTabActions.neighbourOf(b, tabs))
        assertSame(b, ProjectTabActions.neighbourOf(c, tabs))
        assertSame(b, ProjectTabActions.neighbourOf(a, tabs))
        assertNull(ProjectTabActions.neighbourOf(a, listOf(a)))
        assertSame(a, ProjectTabActions.neighbourOf(b, listOf(a)))
    }

    fun testAddPopupHoldsRecentProjectsAndTheStockWidgetActions() {
        val group = ProjectTabActions.addPopupGroup()
        val children = group.getChildren(null).toList()
        val stock = ActionManager.getInstance().getAction(ProjectTabActions.STOCK_ACTIONS_GROUP)
        assertTrue(stock is ActionGroup)
        assertTrue(children.last() === stock)
        assertTrue(children.any { it is Separator })
        val recent = RecentProjectListActionProvider.getInstance().getActions(addClearListItem = false, useGroups = false)
        assertEquals(recent.size, children.size - 2)
    }

    fun testContextMenuOffersCloseCloseOthersAndCopyPath() {
        val group = ProjectTabActions.contextMenuGroup(project, project, listOf(project))
        assertEquals(
            listOf("tabs.menu.close", "tabs.menu.closeOthers", "tabs.menu.copyPath").map(AgenstormBundle::message),
            group.getChildren(null).map { it.templatePresentation.text },
        )
    }

    fun testCopyPathPutsTheBasePathOnTheClipboard() {
        ProjectTabActions.copyPath(project)
        assertEquals(project.basePath, CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor))
    }

    fun testRightClickOnATabAsksForTheContextMenu() {
        val panel = ProjectTabsPanel(ProjectTabsModel.getInstance())
        var asked: Project? = null
        panel.onContextMenu = { target, _, _ -> asked = target }
        val label = panel.tabLabels().single { it.project === project }

        label.dispatchEvent(MouseEvent(label, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 3, 3, 1, false, MouseEvent.BUTTON3))

        assertSame(project, asked)
        assertNotNull(Point(3, 3))
    }
}
