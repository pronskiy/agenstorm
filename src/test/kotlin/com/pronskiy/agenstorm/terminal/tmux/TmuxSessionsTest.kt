package com.pronskiy.agenstorm.terminal.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step U1.3, on output captured from tmux 3.7c (`list-panes -a -F` with [TmuxSessions.FORMAT]). */
class TmuxSessionsTest {

    /** A captured line; `@agenstorm_name`, the last field, is empty unless given. */
    private fun line(vararg fields: String) = (fields.toList() + List(12 - fields.size) { "" }).joinToString("\u001f")

    private val host = "Romans-MacBook-Pro.local"

    private val captured = listOf(
        line("app-1", "0", "1790850148", "", "", "", "43651", "bash", "/private/tmp", host, host),
        line("app-2", "0", "1790850148", "/Users/me/app/.git", "/Users/me/app", "1", "43653", "sleep", "/private/tmp", host, host, "Local (2)"),
        line("app-3", "1", "1790850148", "", "", "", "43660", "2.1.286", "/Users/me/app", "✳ Claude Code", host),
    ).joinToString("\n", postfix = "\n")

    @Test
    fun sessionsCarryTheirTagsClientsAndPanes() {
        val sessions = TmuxSessions.parse(captured)

        assertEquals(listOf("app-1", "app-2", "app-3"), sessions.map { it.name })
        val kept = sessions[1]
        assertEquals("/Users/me/app/.git", kept.repository)
        assertEquals("/Users/me/app", kept.project)
        assertTrue(kept.background)
        assertEquals(0, kept.clients)
        assertEquals(1790850148L, kept.createdEpochSeconds)
        assertEquals(TmuxPane(43653, "sleep", "/private/tmp", null), kept.panes.single())
        assertEquals("Local (2)", kept.tabName)
        assertNull(sessions[0].repository)
        assertNull(sessions[0].tabName)
        assertFalse(sessions[0].background)
        assertEquals(1, sessions[2].clients)
    }

    @Test
    fun runningMeansSomethingOtherThanAShellIsInTheForeground() {
        val (idle, sleeping, claude) = TmuxSessions.parse(captured)

        assertFalse(idle.running)
        assertTrue(sleeping.running)
        assertTrue(claude.running)
        assertTrue(TmuxSessions.isShell("-zsh"))
        assertTrue(TmuxSessions.isShell("fish"))
        assertFalse(TmuxSessions.isShell("node"))
    }

    @Test
    fun aSessionIsCalledAfterTheProgramsTitleThenItsCommand() {
        val (idle, sleeping, claude) = TmuxSessions.parse(captured)

        assertEquals("✳ Claude Code", claude.label) // Claude reports its version, 2.1.286, as its command
        assertEquals("sleep", sleeping.label) // tmux's default title, the host name, is not a program's
        assertEquals("bash", idle.label)
        val stale = TmuxSessions.parse(line("app-6", "0", "1", "", "", "", "1", "sleep", "/work/app/.worktrees/fix-login", "app", host))
        assertEquals("a title the shell set before the program started", "sleep", stale.single().label)
        val vim = TmuxSessions.parse(line("app-7", "0", "1", "", "", "", "1", "vim", "/work/app", "Login.php - VIM", host))
        assertEquals("what runs, not what it calls itself", "vim", vim.single().label)
    }

    @Test
    fun aSessionWithSeveralPanesIsOneSession() {
        val sessions = TmuxSessions.parse(
            line("app-4", "1", "1", "", "", "", "100", "bash", "/a", host, host) + "\n" +
                line("app-4", "1", "1", "", "", "", "101", "npm", "/a", host, host),
        )

        assertEquals(1, sessions.size)
        assertEquals(listOf(100L, 101L), sessions.single().panes.map { it.pid })
        assertTrue(sessions.single().running)
        assertEquals("npm", sessions.single().label)
    }

    @Test
    fun theSeparatorEscapedAsTmux34PrintsItIsReadToo() {
        val escaped = captured.replace("\u001f", "\\037")

        assertEquals(TmuxSessions.parse(captured), TmuxSessions.parse(escaped))
        assertEquals("✳ Claude Code", TmuxSessions.parse(escaped)[2].label)
    }

    @Test
    fun noServerOrStrayLinesGiveNothing() {
        assertTrue(TmuxSessions.parse("").isEmpty())
        assertTrue(TmuxSessions.parse("no server running on /tmp/tmux-501/agenstorm\n").isEmpty())
        assertTrue(TmuxSessions.parse(line("app-5", "0", "1", "", "", "", "not-a-pid", "bash", "/a", host, host)).isEmpty())
    }
}
