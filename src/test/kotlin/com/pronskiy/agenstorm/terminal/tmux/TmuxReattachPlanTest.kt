package com.pronskiy.agenstorm.terminal.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step U3.1: which background terminals a reopening project gets tabs for, and which tab is a second one. */
class TmuxReattachPlanTest {

    private fun session(name: String, project: String = "/work/app", background: Boolean = true, clients: Int = 0) =
        TmuxSession(name, clients, createdEpochSeconds = 0, repository = "/work/app/.git", project = project, background = background,
            panes = listOf(TmuxPane(1, "sleep", project, null)))

    @Test
    fun onlyThisProjectsUnattachedBackgroundTerminalsComeBack() {
        val sessions = listOf(
            session("app-a"),
            session("app-b", project = "/work/app/.worktrees/fix-login"), // another worktree: stays listed (U3.3)
            session("app-c", background = false, clients = 1), // an ordinary session
            session("app-d", clients = 1), // a restored tab attached already
            session("app-e"), // a restored tab may ask for it, not started yet: selected rather than opened again
        )

        assertEquals(listOf("app-a", "app-e"), TmuxReattachPlan.kept(sessions, "/work/app").map { it.name })
    }

    @Test
    fun aTabAskingForASessionAnotherTabShowsIsASecondOne() {
        assertTrue(TmuxReattachPlan.isDuplicate("app-a", listOf("app-b", "app-a")))
        assertFalse(TmuxReattachPlan.isDuplicate("app-a", listOf("app-b")))
        assertFalse("a tab not attaching to a session", TmuxReattachPlan.isDuplicate(null, listOf(null, "app-a")))
    }
}
