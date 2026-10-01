package com.pronskiy.agenstorm.terminal.tmux

import com.pronskiy.agenstorm.terminal.tmux.TmuxBackgroundPlan.Shown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Step U3.2: what a project close or a quit puts at stake, and how a session is kept. */
class TmuxBackgroundPlanTest {

    private fun session(name: String, command: String = "sleep", clients: Int = 1, project: String? = "/work/app") =
        TmuxSession(name, clients, createdEpochSeconds = 0, repository = "/work/app/.git", project = project, background = false,
            panes = listOf(TmuxPane(1, command, "/work/app", null)))

    private fun shown(vararg sessions: String) = sessions.map { Shown(it, "Local", "Local") }

    @Test
    fun aRunningSessionNoOtherClientShowsIsAtStake() {
        val sessions = listOf(
            session("app-a"),
            session("app-b", command = "zsh"), // idle: ends with its tab, nothing to ask
            session("app-c", clients = 2), // shown in another window too
            session("app-d", clients = 2), // two tabs of this window
            session("app-e"), // no tab of this window
        )

        assertEquals(listOf("app-a", "app-d"), TmuxBackgroundPlan.atStake(sessions, shown("app-a", "app-b", "app-c", "app-d", "app-d"), "/work/app").map { it.name })
    }

    @Test
    fun aSessionAWorktreeSwitchHandedOnIsNotThisProjectsAnyMore() {
        val sessions = listOf(session("app-a", project = "/work/app/.worktrees/fix-login"), session("app-b", project = null))

        assertEquals(listOf("app-b"), TmuxBackgroundPlan.atStake(sessions, shown("app-a", "app-b"), "/work/app").map { it.name })
        assertEquals("a quit closes every window", listOf("app-a", "app-b"), TmuxBackgroundPlan.atStake(sessions, shown("app-a", "app-b"), null).map { it.name })
    }

    @Test
    fun keepingTurnsDestroyUnattachedOffTagsItAndRemembersTheTabsName() {
        assertEquals(
            listOf("set-option", "-t", "=app-a:", "destroy-unattached", "off", ";", "set-option", "-t", "=app-a:", "@agenstorm_background", "1",
                ";", "set-option", "-t", "=app-a:", "@agenstorm_name", "Local (2)"),
            TmuxBackgroundPlan.keep("app-a", "Local (2)"),
        )
        assertEquals(11, TmuxBackgroundPlan.keep("app-a", null).size)
        assertEquals(listOf("kill-session", "-t", "=app-a"), TmuxBackgroundPlan.stop("app-a"))
    }

    @Test
    fun aSessionIsNamedAfterItsWorktreesFolder() {
        assertEquals("fix-login", TmuxBackgroundPlan.worktreeName(session("a", project = "/work/app/.worktrees/fix-login/")))
        assertNull(TmuxBackgroundPlan.worktreeName(session("a", project = null)))
    }
}
