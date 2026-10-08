package com.pronskiy.agenstorm.terminal.agents

import org.junit.Assert.assertEquals
import org.junit.Test

/** Step X2.3: a session's tab by its tmux session's name or by the processes under a tab's shell. */
class AgentTabLocatorTest {

    private fun session(pid: Long, tmux: String? = null) =
        LiveSession(pid = pid, sessionId = "s$pid", cwd = "/w/app", name = null, status = null, tmuxSession = tmux, updatedAt = null, entrypoint = null)

    private val plain = TabProbe("plain", shellPid = 87118, tmuxSession = null)
    private val tmuxTab = TabProbe("tmux", shellPid = 87122, tmuxSession = "app-28un")
    private val notStarted = TabProbe("new", shellPid = null, tmuxSession = null)
    private val tabs = listOf(notStarted, plain, tmuxTab)

    /** 87118 runs claude 89173 under a node wrapper; the tmux client 87122 has nothing under it. */
    private val tree = mapOf(87118L to setOf(89100L, 89173L))

    @Test
    fun aPlainTabByThePidUnderItsShell() {
        assertEquals(SessionPlace.InTab("plain"), AgentTabLocator.locate(session(89173), tabs, emptySet()) { tree[it].orEmpty() })
    }

    @Test
    fun aTmuxTabByTheSessionsName() {
        assertEquals(SessionPlace.InTab("tmux"), AgentTabLocator.locate(session(9, tmux = "app-28un"), tabs, emptySet()) { tree[it].orEmpty() })
    }

    @Test
    fun aTmuxSessionNoTabShowsIsABackgroundTerminalIfItIsOurs() {
        assertEquals(SessionPlace.Background("app-qktg"), AgentTabLocator.locate(session(9, tmux = "app-qktg"), tabs, setOf("app-qktg")) { emptySet() })
        assertEquals("someone else's tmux", SessionPlace.Elsewhere, AgentTabLocator.locate(session(9, tmux = "main"), tabs, setOf("app-qktg")) { emptySet() })
    }

    @Test
    fun aTmuxSessionIsNotLookedForByPid() {
        val inside = TabProbe("plain", shellPid = 1, tmuxSession = null)

        assertEquals(SessionPlace.Elsewhere, AgentTabLocator.locate(session(5, tmux = "x"), listOf(inside), emptySet()) { setOf(5L) })
    }

    @Test
    fun aPidUnderNoTabIsElsewhere() {
        assertEquals(SessionPlace.Elsewhere, AgentTabLocator.locate(session(40915), tabs, emptySet()) { tree[it].orEmpty() })
    }

    @Test
    fun eachShellIsAskedOnce() {
        val asked = mutableListOf<Long>()

        val places = AgentTabLocator.locateAll(listOf(session(89173), session(89100), session(1)), tabs, emptySet()) { asked += it; tree[it].orEmpty() }

        assertEquals(listOf(87118L), asked)
        assertEquals(SessionPlace.InTab("plain"), places["s89173"])
        assertEquals(SessionPlace.Elsewhere, places["s1"])
    }

    @Test
    fun thisProcessHasNoDescendantsItDidNotStart() {
        assertEquals(emptySet<Long>(), AgentTabLocator.descendantsOf(Long.MAX_VALUE))
    }
}
