package com.pronskiy.agenstorm.tabs

import com.intellij.ide.ReopenProjectAction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.popup.ActionPopupStep
import com.pronskiy.agenstorm.tabs.ui.AddProjectPopup
import javax.swing.JPanel

/** The "+" popup: the recent projects scroll in a list of their own, the stock actions stay in view (Roman, 2026-10-19). */
class AddProjectPopupTest : BasePlatformTestCase() {

    fun testProjectsAndActionsGoToTheirOwnListsAndOnlyTheProjectsScroll() {
        val popup = popupOf(projects = 15)
        assertEquals(15, popup.projects.model.size)
        assertEquals(listOf("Open…", "Get from Version Control…"), (0 until popup.commands.model.size).map { popup.commands.model.getElementAt(it).text })
        assertEquals(AddProjectPopup.MAX_VISIBLE_PROJECTS, popup.projects.visibleRowCount)
        assertEquals("the first project is selected", 0, popup.projects.selectedIndex)
    }

    fun testAFewProjectsTakeOnlyTheRoomTheyNeed() {
        assertEquals(3, popupOf(projects = 3).projects.visibleRowCount)
    }

    fun testProjectsSharingANameShowTheirParentFolder() {
        val group = DefaultActionGroup()
        val first = ReopenProjectAction("/work/SomeFolder/infra", "infra", "infra")
        val second = ReopenProjectAction("/home/AnotherFolder/infra", "infra", "infra")
        val unique = ReopenProjectAction("/work/alpha", "alpha", "alpha")
        group.add(first)
        group.add(second)
        group.add(unique)
        val popup = popupOf(group)
        assertEquals("SomeFolder/infra", popup.nameOf(first))
        assertEquals("AnotherFolder/infra", popup.nameOf(second))
        assertEquals("a name no one shares is shown alone", "alpha", popup.nameOf(unique))
    }

    fun testTheArrowsRunThroughBothListsAndWrapAround() {
        val popup = popupOf(projects = 3)
        popup.move(-1)
        assertEquals("up from the first project: the last action", 1, popup.commands.selectedIndex)
        assertEquals(-1, popup.projects.selectedIndex)
        popup.move(1)
        assertEquals("and down again: the first project", 0, popup.projects.selectedIndex)
        assertEquals(-1, popup.commands.selectedIndex)
        popup.move(1)
        popup.move(1)
        popup.move(1)
        assertEquals("down from the last project: the first action", 0, popup.commands.selectedIndex)
        assertEquals(-1, popup.projects.selectedIndex)
    }

    private fun popupOf(projects: Int): AddProjectPopup {
        val group = DefaultActionGroup()
        repeat(projects) { group.add(ReopenProjectAction("/fake/p$it", "p$it", "p$it")) }
        group.addSeparator()
        group.add(plain("Open…"))
        group.add(plain("Get from Version Control…"))
        return popupOf(group)
    }

    private fun popupOf(group: DefaultActionGroup): AddProjectPopup {
        val context = SimpleDataContext.getProjectContext(project)
        val step = JBPopupFactory.getInstance().createActionsStep(group, context, "test", false, true, null, JPanel(), false, 0, false)
        return AddProjectPopup(step as ActionPopupStep) { null }.also { Disposer.register(testRootDisposable, it.popup) }
    }

    private fun plain(text: String): AnAction = object : DumbAwareAction(text) {
        override fun actionPerformed(e: AnActionEvent) = Unit
    }
}
