package com.pronskiy.agenstorm.terminal.tmux

import com.pronskiy.agenstorm.terminal.tmux.TmuxHandOffPlan.Move
import com.pronskiy.agenstorm.terminal.tmux.TmuxHandOffPlan.Tab
import org.junit.Assert.assertEquals
import org.junit.Test

/** Step U2.2: which sessions move on a switch, once each, within the repository, and what their new tabs are called. */
class TmuxHandOffPlanTest {

    private val repo = "/work/app/.git"

    private fun session(name: String, command: String, repository: String? = repo, title: String? = null) =
        TmuxSession(name, clients = 1, createdEpochSeconds = 0, repository = repository, project = "/work/app", background = false,
            panes = listOf(TmuxPane(1, command, "/work/app", title)))

    @Test
    fun onlyTheSessionsRunningSomethingMove() {
        val sessions = listOf(session("app-1", "bash"), session("app-2", "2.1.286", title = "✳ Claude Code"), session("app-3", "npm"))
        val tabs = listOf(Tab("app-1", null, "Local"), Tab("app-2", null, "Local (2)"), Tab("app-3", null, "Local (3)"))

        assertEquals(
            listOf(Move("app-2", "Local (2)", null), Move("app-3", "Local (3)", null)),
            TmuxHandOffPlan.plan(tabs, sessions, repo),
        )
    }

    @Test
    fun aRenameIsKeptAndASessionShownTwiceMovesOnce() {
        val sessions = listOf(session("app-2", "sleep"))
        val tabs = listOf(Tab("app-2", "agent", "Local"), Tab("app-2", null, "Local (2)"))

        assertEquals(listOf(Move("app-2", "agent", "agent")), TmuxHandOffPlan.plan(tabs, sessions, repo))
    }

    @Test
    fun anotherRepositorysSessionOrOneThatIsGoneStays() {
        val sessions = listOf(session("shop-1", "sleep", repository = "/work/shop/.git"))
        val tabs = listOf(Tab("shop-1", null, "Local"), Tab("app-9", null, "Local (2)"))

        assertEquals(emptyList<Move>(), TmuxHandOffPlan.plan(tabs, sessions, repo))
        assertEquals(listOf("shop-1"), TmuxHandOffPlan.plan(tabs, sessions, repository = null).map { it.session })
    }

    @Test
    fun withoutAnyNameTheTabIsCalledAfterWhatRuns() {
        val sessions = listOf(session("app-2", "2.1.286", title = "✳ Claude Code"))

        assertEquals("✳ Claude Code", TmuxHandOffPlan.plan(listOf(Tab("app-2", null, "")), sessions, repo).single().name)
    }
}
