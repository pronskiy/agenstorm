package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.openapi.util.io.NioFiles
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Step U1.4: what gets wrapped, the session's name, the command line — and that real tmux tags an attached session. */
class TmuxShellCommandTest {

    private var temp: Path? = null
    private var tmux: Path? = null
    private var client: PtyProcess? = null
    private val socket = "agenstorm-test-${ProcessHandle.current().pid()}"

    @After
    fun tearDown() {
        client?.destroyForcibly()
        tmux?.let { ProcessBuilder(it.toString(), "-L", socket, "kill-server").start().waitFor() }
        temp?.let(NioFiles::deleteRecursively)
    }

    @Test
    fun onlyAShellIsWrapped() {
        assertTrue(TmuxShellCommand.isShell(listOf("/bin/zsh", "-l", "-i")))
        assertTrue(TmuxShellCommand.isShell(listOf("/bin/bash", "--rcfile", "/x/bash-integration.bash", "-i")))
        assertFalse(TmuxShellCommand.isShell(listOf("/opt/homebrew/bin/tmux", "-L", "agenstorm", "attach-session", "-t", "app-1")))
        assertFalse(TmuxShellCommand.isShell(listOf("/usr/local/bin/node", "server.js")))
        assertFalse(TmuxShellCommand.isShell(emptyList()))
    }

    @Test
    fun aTabsSessionIsReadFromItsCommand() {
        val tmux = listOf("/opt/homebrew/bin/tmux", "-u", "-L", "agenstorm", "-f", "/sys/tmux.conf")

        assertEquals("app-2", TmuxShellCommand.sessionOf(tmux + listOf("new-session", "-s", "app-2", "-c", "/a", "-e", "X=-t", "--", "/bin/zsh", "-s", ";", "set-option", "@agenstorm_repo", "/a/.git")))
        assertEquals("app-1", TmuxShellCommand.sessionOf(tmux + listOf("attach-session", "-t", "app-1")))
        assertEquals(null, TmuxShellCommand.sessionOf(listOf("/opt/homebrew/bin/tmux", "-L", "work", "attach-session", "-t", "app-1")))
        assertEquals(null, TmuxShellCommand.sessionOf(listOf("/bin/zsh", "-l", "-i")))
    }

    @Test
    fun sessionsAreNamedAfterTheProjectWithTheFirstFreeNumber() {
        assertEquals("app-1", TmuxShellCommand.sessionName("app", emptySet()))
        assertEquals("app-3", TmuxShellCommand.sessionName("app", setOf("app-1", "app-2", "shop-3")))
        assertEquals("my-app-v2-1", TmuxShellCommand.sessionName("my app.v2", emptySet()))
        assertEquals("terminal-1", TmuxShellCommand.sessionName("...", emptySet()))
    }

    @Test
    fun theShellRunsInANewTaggedSessionWithItsEnvironment() {
        val command = TmuxShellCommand.wrap(
            tmux = listOf("tmux", "-u", "-L", "agenstorm", "-f", "/sys/tmux.conf"),
            session = "app-1",
            workingDirectory = "/work/app",
            env = mapOf("ZDOTDIR" to "/ide/zsh", "TERM" to "xterm-256color", "A" to "1"),
            shell = listOf("/bin/zsh", "-l", "-i"),
            repository = "/work/app/.git",
            project = "/work/app",
        )

        assertEquals(
            listOf(
                "tmux", "-u", "-L", "agenstorm", "-f", "/sys/tmux.conf", "new-session", "-s", "app-1", "-c", "/work/app",
                "-e", "A=1", "-e", "ZDOTDIR=/ide/zsh", "--", "/bin/zsh", "-l", "-i",
                ";", "set-option", "@agenstorm_repo", "/work/app/.git", ";", "set-option", "@agenstorm_project", "/work/app",
            ),
            command,
        )
    }

    @Test
    fun realTmuxRunsTheWrappedShellInATaggedSessionThatEndsWithItsTab() {
        val binary = Tmux.find(System.getenv("PATH")) { Files.isRegularFile(it) && Files.isExecutable(it) }
        assumeNotNull(binary)
        tmux = binary
        temp = Files.createTempDirectory("tmux-wrap")
        val config = Files.writeString(temp!!.resolve("tmux.conf"), Tmux.configText(null))
        val prefix = listOf(binary.toString(), "-u", "-L", socket, "-f", config.toString())
        val command = TmuxShellCommand.wrap(
            prefix, "app-1", temp.toString(), mapOf("AGENSTORM_PROBE" to "42"), listOf("sleep", "30"),
            "/work/app/.git", "/work/app",
        )

        // A tab is a pty: the tmux client attaches to the session it creates.
        client = PtyProcessBuilder(command.toTypedArray()).setEnvironment(System.getenv() + ("TERM" to "xterm-256color")).start()
        fun sessions() = TmuxSessions.parse(run(prefix + listOf("list-panes", "-a", "-F", TmuxSessions.FORMAT)))
        val session = waitFor { sessions().firstOrNull { it.name == "app-1" && it.repository != null && it.panes.any { p -> p.command == "sleep" } } }

        assertNotNull("no tagged session in ${sessions()}", session)
        assertEquals("/work/app/.git", session!!.repository)
        assertEquals("/work/app", session.project)
        assertEquals(1, session.clients)
        assertTrue(session.running)
        assertEquals("AGENSTORM_PROBE=42", run(prefix + listOf("show-environment", "-t", "app-1", "AGENSTORM_PROBE")).trim())

        // Decision 91: closing the tab (its client) ends the session.
        client!!.destroyForcibly().waitFor()
        assertNotNull("app-1 still there: ${sessions()}", waitFor { sessions().none { it.name == "app-1" }.takeIf { it } })
    }

    private fun <T : Any> waitFor(seconds: Int = 10, probe: () -> T?): T? {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        return process.inputStream.bufferedReader().readText().also { process.waitFor() }
    }
}
