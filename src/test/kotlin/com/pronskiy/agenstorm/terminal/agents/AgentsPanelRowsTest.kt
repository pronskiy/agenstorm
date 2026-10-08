package com.pronskiy.agenstorm.terminal.agents

import com.intellij.openapi.actionSystem.CustomizedDataContext
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.AnimatedIcon
import javax.swing.tree.DefaultMutableTreeNode

/** Steps X2.5 and X2.6: sessions under their project, updated in place, and a click on one handed on with its project. */
class AgentsPanelRowsTest : BasePlatformTestCase() {

    private lateinit var parent: com.intellij.openapi.Disposable
    private lateinit var panel: AgentsPanel
    private val clicks = mutableListOf<Pair<String, String>>()

    private fun row(id: String, status: String = "idle", place: SessionPlace<ProjectTab> = SessionPlace.Elsewhere, finished: Boolean = false) =
        SessionRow(LiveSession(pid = 1, sessionId = id, cwd = "/w/app", name = id, status = status, tmuxSession = null, updatedAt = null, entrypoint = "cli"), place, finished)

    private fun groups(vararg app: SessionRow) = listOf(ProjectGroup("app", "/w/app", app.toList()), ProjectGroup("other", "/w/other"))

    override fun setUp() {
        super.setUp()
        AgentsSidebarState.getInstance().loadState(AgentsSidebarState.Layout())
        parent = Disposer.newDisposable()
        panel = AgentsPanel(project, parent, isFront = { true })
        panel.onClick = { row, group -> clicks += row.session.sessionId to group.basePath }
    }

    override fun tearDown() {
        try {
            Disposer.dispose(parent)
            AgentsSidebarState.getInstance().loadState(AgentsSidebarState.Layout())
        } finally {
            super.tearDown()
        }
    }

    private fun children(row: Int): List<SessionRow> {
        val node = panel.tree.getPathForRow(row).lastPathComponent as DefaultMutableTreeNode
        return (0 until node.childCount).map { (node.getChildAt(it) as DefaultMutableTreeNode).userObject as SessionRow }
    }

    fun testSessionsAreRowsUnderTheirProject() {
        panel.render(groups(row("a"), row("b")))

        assertEquals(listOf("a", "b"), children(0).map { it.session.sessionId })
        assertEquals("app, a, b, other", 4, panel.tree.rowCount)
    }

    fun testAStatusChangeKeepsTheSelectedRow() {
        panel.render(groups(row("a"), row("b")))
        panel.tree.setSelectionRow(2)

        panel.render(groups(row("a"), row("b", status = "busy")))

        assertEquals("session:b", panel.tree.selectionPath?.let(AgentsPanel::idOf))
        assertEquals("busy", children(0)[1].session.status)
    }

    fun testANewSessionRebuildsAndTheSelectionFollowsTheSharedLayout() {
        panel.render(groups(row("a")))
        panel.tree.setSelectionRow(1)

        panel.render(groups(row("a"), row("new")))

        assertEquals("session:a", panel.tree.selectionPath?.let(AgentsPanel::idOf))
        assertEquals("app, a, new, other", 4, panel.tree.rowCount)
    }

    fun testAClickOnASessionGoesOutWithItsProjectAndOnAProjectDoesNothing() {
        panel.render(groups(row("a")))

        panel.click(panel.tree.getPathForRow(1))
        panel.click(panel.tree.getPathForRow(0))

        assertEquals(listOf("a" to "/w/app"), clicks)
    }

    fun testStatusMarks() {
        assertSame(AnimatedIcon.Default.INSTANCE, AgentsPanel.iconOf(row("a", status = "busy")))
        assertNotSame(AgentsPanel.iconOf(row("a", status = "idle")), AgentsPanel.iconOf(row("a", status = "idle", finished = true)))
        assertNotSame(AgentsPanel.iconOf(row("a", status = "idle")), AgentsPanel.iconOf(row("a", status = "waiting")))
        assertSame("a status Claude may add later looks idle", AgentsPanel.iconOf(row("a", status = "idle")), AgentsPanel.iconOf(row("a", status = "compacting")))
    }

    fun testTheSelectedSessionsIdIsThereForCopySessionId() {
        panel.render(groups(row("a")))
        panel.tree.setSelectionRow(1)

        val context: DataContext = CustomizedDataContext.withSnapshot(DataContext.EMPTY_CONTEXT) { sink -> panel.uiDataSnapshot(sink) }

        assertEquals("a", context.getData(AgentsPanel.SESSION_ID))
    }

    fun testClickPlans() {
        val tab = fakeTab(project)
        assertEquals(ClickPlan.Focus(tab), ClickPlan.of(row("a", place = SessionPlace.InTab(tab)), "/w/app"))
        assertEquals(ClickPlan.Background("app-1a2b", "/w/app"), ClickPlan.of(row("a", place = SessionPlace.Background("app-1a2b")), "/w/app"))
        assertEquals(ClickPlan.Nothing, ClickPlan.of(row("a"), "/w/app"))
    }
}
