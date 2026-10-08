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
    private val clicks = mutableListOf<Pair<ClickPlan, String>>()

    private fun row(id: String, status: String = "idle", place: SessionPlace<ProjectTab> = SessionPlace.Elsewhere, finished: Boolean = false) =
        SessionRow(LiveSession(pid = 1, sessionId = id, cwd = "/w/app", name = id, status = status, tmuxSession = null, updatedAt = null, entrypoint = "cli"), place, finished)

    private fun groups(vararg app: SessionRow, history: List<PastSession> = emptyList()) =
        listOf(ProjectGroup("app", "/w/app", app.toList(), history), ProjectGroup("other", "/w/other"))

    private fun past(id: String) = PastSession(id, "/w/app", "title $id", lastActivity = 0)

    override fun setUp() {
        super.setUp()
        AgentsSidebarState.getInstance().loadState(AgentsSidebarState.Layout())
        parent = Disposer.newDisposable()
        panel = AgentsPanel(project, parent, isFront = { true })
        panel.onClick = { plan, id -> clicks += plan to id }
    }

    override fun tearDown() {
        try {
            Disposer.dispose(parent)
            AgentsSidebarState.getInstance().loadState(AgentsSidebarState.Layout())
        } finally {
            super.tearDown()
        }
    }

    private fun children(row: Int): List<SessionRow> = items(row).filterIsInstance<SessionRow>()

    private fun items(row: Int): List<Any> {
        val node = panel.tree.getPathForRow(row).lastPathComponent as DefaultMutableTreeNode
        return (0 until node.childCount).map { (node.getChildAt(it) as DefaultMutableTreeNode).userObject }
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

        assertEquals("a session outside the IDE: nothing to do, but the click is still told", listOf(ClickPlan.Nothing to "a"), clicks)
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

    fun testPastSessionsComeAfterTheRunningOnes() {
        panel.render(groups(row("a"), history = listOf(past("p1"), past("p2"))))

        assertEquals(listOf("a", "p1", "p2"), items(0).map { (it as? SessionRow)?.session?.sessionId ?: (it as PastSession).sessionId })
        assertEquals("past:p1", AgentsPanel.idOf(panel.tree.getPathForRow(2)))
    }

    fun testAPastSessionThatEndsLeavesTheRestInPlace() {
        panel.render(groups(row("a"), history = listOf(past("p1"))))
        panel.tree.setSelectionRow(2)

        panel.render(groups(row("a"), history = listOf(past("p1").copy(title = "renamed"))))

        assertEquals("past:p1", panel.tree.selectionPath?.let(AgentsPanel::idOf))
        assertEquals("renamed", (items(0)[1] as PastSession).title)
    }

    fun testAClickOnAPastSessionResumesItInItsProject() {
        panel.render(groups(row("a"), history = listOf(past("p1"))))

        panel.click(panel.tree.getPathForRow(2))

        assertEquals(listOf(ClickPlan.Resume("/w/app", "/w/app", "p1", "title p1") to "p1"), clicks)
    }

    fun testCopySessionIdWorksOnAPastSessionToo() {
        panel.render(groups(history = listOf(past("p1"))))
        panel.tree.setSelectionRow(1)

        assertEquals("p1", CustomizedDataContext.withSnapshot(DataContext.EMPTY_CONTEXT) { sink -> panel.uiDataSnapshot(sink) }.getData(AgentsPanel.SESSION_ID))
    }

    fun testOnlyASessionIdIsEverTyped() {
        assertEquals("claude --resume aaaaaaaa-1111-4111-8111-111111111111", AgentResume.command("aaaaaaaa-1111-4111-8111-111111111111"))
        assertNull(AgentResume.command("x; rm -rf ~"))
        assertNull(AgentResume.command("aaaaaaaa-1111-4111-8111-111111111111\necho"))
    }

    fun testARowLetsGoOfAClosingProjectsTab() {
        val tab = fakeTab(project)
        val inTab = row("a", place = SessionPlace.InTab(tab))

        assertEquals(SessionPlace.Elsewhere, inTab.withoutTabOf(project).place)
        assertSame(inTab, inTab.withoutTabOf(null))
        assertSame("its project is not disposed", inTab, inTab.withoutClosedTab())
    }
}
