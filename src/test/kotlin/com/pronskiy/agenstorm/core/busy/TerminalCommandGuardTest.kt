package com.pronskiy.agenstorm.core.busy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step U2.3: which reworked terminal tabs keep a project open — never one whose process is a tmux client. */
class TerminalCommandGuardTest {

    private val zsh = listOf("/bin/zsh", "-l", "-i")
    private val tmuxNew = listOf("/opt/homebrew/bin/tmux", "-u", "-L", "agenstorm", "-f", "/c", "new-session", "-s", "app-1", "--", "/bin/zsh", "-l", "-i")
    private val tmuxAttach = listOf("/opt/homebrew/bin/tmux", "-u", "-L", "agenstorm", "-f", "/c", "attach-session", "-t", "=app-1")

    @Test
    fun aShellIsBusyWhileItExecutesACommand() {
        assertTrue(TerminalCommandGuard.tabBusy(zsh, nonShell = false, executing = true))
        assertFalse(TerminalCommandGuard.tabBusy(zsh, nonShell = false, executing = false))
    }

    @Test
    fun aNonShellProgramIsBusyWhileItsTabIsThere() {
        assertTrue(TerminalCommandGuard.tabBusy(listOf("/usr/local/bin/node", "server.js"), nonShell = true, executing = false))
    }

    @Test
    fun aTmuxClientIsNeverBusyItsWorkOutlivesTheClose() {
        assertFalse(TerminalCommandGuard.tabBusy(tmuxNew, nonShell = false, executing = true))
        assertFalse(TerminalCommandGuard.tabBusy(tmuxAttach, nonShell = true, executing = false))
    }
}
