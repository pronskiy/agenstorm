package com.pronskiy.agenstorm.terminal.agents

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.pronskiy.agenstorm.core.AgenstormSettings

/** Step X1.2: the sidebar follows the setting, lists the open projects and marks its own. */
class AgentsToolWindowTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testAvailableOnlyWhileTheSettingIsOn() {
        val factory = AgentsToolWindowFactory()
        assertFalse(factory.shouldBeAvailable(project))

        AgenstormSettings.getInstance().state.agentSessionsEnabled = true
        assertTrue(factory.shouldBeAvailable(project))
    }

    fun testTheModelHasAGroupPerOpenProjectAndDropsOneClosing() {
        val model = AgentSessionsModel.getInstance()

        model.refresh()
        assertTrue(model.groups.value.any { it.basePath == project.basePath })

        model.refresh(closing = project)
        assertFalse(model.groups.value.any { it.basePath == project.basePath })
        model.refresh()
    }

    fun testThePanelShowsTheModelAndBoldsItsOwnProject() {
        val parent = Disposer.newDisposable()
        try {
            val panel = AgentsPanel(project, parent)
            val other = ProjectGroup("other", "/elsewhere/other")
            val own = ProjectGroup(project.name, project.basePath!!)
            panel.render(ProjectGroup.sorted(listOf(other, own)))

            assertEquals(ProjectGroup.sorted(listOf(other, own)), panel.shownGroups())
            assertEquals(SimpleTextAttributes.STYLE_BOLD, styleOf(panel, panel.shownGroups().indexOf(own)))
            assertEquals(SimpleTextAttributes.STYLE_PLAIN, styleOf(panel, panel.shownGroups().indexOf(other)))
        } finally {
            Disposer.dispose(parent)
        }
    }

    fun testThePanelFollowsTheModel() {
        val parent = Disposer.newDisposable()
        try {
            val panel = AgentsPanel(project, parent)
            AgentSessionsModel.getInstance().refresh()
            PlatformTestUtil.waitWithEventsDispatching("the panel never showed the project", { panel.shownGroups().any { it.basePath == project.basePath } }, 5)

            assertTrue(panel.shownGroups().any { it.basePath == project.basePath })
        } finally {
            Disposer.dispose(parent)
        }
    }

    private fun styleOf(panel: AgentsPanel, row: Int): Int {
        val tree = panel.tree
        val renderer = tree.cellRenderer as ColoredTreeCellRenderer
        val path = tree.getPathForRow(row)
        renderer.getTreeCellRendererComponent(tree, path.lastPathComponent, false, false, true, row, false)
        val iterator = renderer.iterator()
        iterator.next()
        return iterator.textAttributes.style
    }
}
