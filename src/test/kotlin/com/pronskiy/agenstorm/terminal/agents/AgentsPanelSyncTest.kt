package com.pronskiy.agenstorm.terminal.agents

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.tree.TreePath

/** Step X1.4: the selected row is the same in every window; only the window in front changes it. */
class AgentsPanelSyncTest : BasePlatformTestCase() {

    private val groups = ProjectGroup.sorted(listOf(ProjectGroup("alpha", "/w/alpha"), ProjectGroup("beta", "/w/beta")))
    private lateinit var parent: com.intellij.openapi.Disposable

    override fun setUp() {
        super.setUp()
        parent = Disposer.newDisposable()
        AgentsSidebarState.getInstance().loadState(AgentsSidebarState.Layout())
    }

    override fun tearDown() {
        try {
            Disposer.dispose(parent)
            AgentsSidebarState.getInstance().loadState(AgentsSidebarState.Layout())
        } finally {
            super.tearDown()
        }
    }

    fun testASelectionInFrontReachesThePanelBehind() {
        val inFront = AgentsPanel(project, parent, isFront = { true }).also { it.render(groups) }
        val behind = AgentsPanel(project, parent, isFront = { false }).also { it.render(groups) }

        inFront.tree.setSelectionRow(1)
        behind.restore(AgentsSidebarState.getInstance().layout.value)

        assertEquals("group:/w/beta", AgentsSidebarState.getInstance().layout.value.selected)
        assertEquals("group:/w/beta", behind.tree.selectionPath?.let(AgentsPanel::idOf))
    }

    fun testAPanelBehindDoesNotRecord() {
        val behind = AgentsPanel(project, parent, isFront = { false }).also { it.render(groups) }

        behind.tree.setSelectionRow(0)

        assertEquals("", AgentsSidebarState.getInstance().layout.value.selected)
    }

    fun testRestoringDoesNotEchoBack() {
        val inFront = AgentsPanel(project, parent, isFront = { true }).also { it.render(groups) }
        AgentsSidebarState.getInstance().update { it.copy(selected = "group:/w/alpha") }

        inFront.restore(AgentsSidebarState.getInstance().layout.value)
        AgentsSidebarState.getInstance().update { it.copy(selected = "group:/w/beta") }
        inFront.restore(AgentsSidebarState.getInstance().layout.value)

        assertEquals("group:/w/beta", AgentsSidebarState.getInstance().layout.value.selected)
        assertEquals(TreePath(arrayOf(inFront.tree.model.root, (inFront.tree.model.root as javax.swing.tree.DefaultMutableTreeNode).getChildAt(1))), inFront.tree.selectionPath)
    }

    fun testAProjectThatLeavesTakesItsSelectionWithIt() {
        val panel = AgentsPanel(project, parent, isFront = { true }).also { it.render(groups) }
        panel.tree.setSelectionRow(1)

        panel.render(groups.take(1))

        assertNull(panel.tree.selectionPath)
    }
}
