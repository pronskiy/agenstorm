package com.pronskiy.agenstorm.tabs

import com.intellij.ide.RecentProjectListActionProvider
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.tabs.ui.ProjectTabsPanel
import java.awt.Point
import java.awt.datatransfer.DataFlavor
import java.awt.event.MouseEvent

/** Step E1.4: the pure parts of the tab interactions (neighbour choice, popup contents, clipboard, right click). */
class ProjectTabActionsTest : BasePlatformTestCase() {

    fun testNeighbourIsTheNextLoadedTabThenThePreviousOneThenNothing() {
        val a = project
        val b = FakeProjectHolder.another(a, "b")
        val c = FakeProjectHolder.another(a, "c")
        val tabs = listOf(a, b, c).map { ProjectTab.Loaded(it) }
        assertEquals(ProjectTab.Loaded(c), ProjectTabActions.neighbourTabOf(b, tabs))
        assertEquals(ProjectTab.Loaded(b), ProjectTabActions.neighbourTabOf(c, tabs))
        assertEquals(ProjectTab.Loaded(b), ProjectTabActions.neighbourTabOf(a, tabs))
        assertNull(ProjectTabActions.neighbourTabOf(a, listOf(ProjectTab.Loaded(a))))
        assertEquals(ProjectTab.Loaded(a), ProjectTabActions.neighbourTabOf(b, listOf(ProjectTab.Loaded(a))))
    }

    /** Closing the last loaded project with bookmarks left used to leave no window at all (Roman, 2026-09-19). */
    fun testWithNoLoadedNeighbourTheNearestBookmarkIsTheNeighbour() {
        val a = project
        val b = ProjectTab.Offloaded("/fake/b", "b", 0L)
        val c = ProjectTab.Offloaded("/fake/c", "c", 0L)
        val d = FakeProjectHolder.another(a, "d")

        assertEquals("a loaded tab wins over a nearer bookmark", ProjectTab.Loaded(d), ProjectTabActions.neighbourTabOf(a, listOf(ProjectTab.Loaded(a), b, ProjectTab.Loaded(d))))
        assertEquals("next bookmark first", b, ProjectTabActions.neighbourTabOf(a, listOf(ProjectTab.Loaded(a), b, c)))
        assertEquals("else the previous one", c, ProjectTabActions.neighbourTabOf(a, listOf(b, c, ProjectTab.Loaded(a))))
        assertNull(ProjectTabActions.neighbourTabOf(a, listOf(ProjectTab.Loaded(a))))
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

    fun testContextMenuOfALoadedTabOffersCloseCloseOthersOffloadAndCopyPath() {
        val group = ProjectTabActions.contextMenuGroup(ProjectTab.Loaded(project), project, listOf(ProjectTab.Loaded(project)))
        assertEquals(
            listOf("tabs.menu.close", "tabs.menu.closeOthers", "tabs.menu.offload", "tabs.menu.copyPath").map(AgenstormBundle::message),
            group.getChildren(null).map { it.templatePresentation.text },
        )
    }

    /** Step P2.7. */
    fun testContextMenuOfAnOffloadedTabOffersLoadForgetAndCopyPath() {
        val group = ProjectTabActions.contextMenuGroup(ProjectTab.Offloaded("/fake/beta", "beta", 0L), project, listOf(ProjectTab.Loaded(project)))
        assertEquals(
            listOf("tabs.menu.load", "tabs.menu.forget", "tabs.menu.copyPath").map(AgenstormBundle::message),
            group.getChildren(null).map { it.templatePresentation.text },
        )
    }

    fun testCopyPathPutsTheBasePathOnTheClipboard() {
        ProjectTabActions.copyPath(project)
        assertEquals(project.basePath, CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor))
    }

    fun testRightClickOnATabAsksForTheContextMenu() {
        val panel = ProjectTabsPanel(ProjectTabsModel.getInstance())
        var asked: ProjectTab? = null
        panel.onContextMenu = { target, _, _ -> asked = target }
        val label = panel.tabLabels().single { it.project === project }

        label.dispatchEvent(MouseEvent(label, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 3, 3, 1, false, MouseEvent.BUTTON3))

        assertEquals(ProjectTab.Loaded(project), asked)
        assertNotNull(Point(3, 3))
    }
}
