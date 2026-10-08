package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.openapi.util.io.NioFiles
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Steps U2.2 and U3, against real tmux: the commands the hand-off runs keep a session alive between the old tab's client
 * and the new one's, then let it end with its tabs again; a kept one outlives its last tab as a background terminal,
 * and a stopped one ends. The U2 guardrail run found the keep-alive failing silently (`set-option -t =app-1` is no
 * target tmux accepts), so these are the exact command lines [TerminalHandOff] and [TmuxCloseGuard] use.
 */
class TmuxHandOffTmuxTest {

    private var temp: Path? = null
    private var tmux: Path? = null
    private val clients = mutableListOf<PtyProcess>()
    private val socket = "agenstorm-handoff-test-${ProcessHandle.current().pid()}"
    private lateinit var prefix: List<String>

    @After
    fun tearDown() {
        clients.forEach { it.destroyForcibly() }
        tmux?.let { ProcessBuilder(it.toString(), "-L", socket, "kill-server").start().waitFor() }
        temp?.let(NioFiles::deleteRecursively)
    }

    @Test
    fun aMovingSessionOutlivesTheOldTabAndEndsWithTheNewOne() {
        start()
        val old = client(prefix + listOf("new-session", "-s", "app-1", "sleep", "60"))
        assertNotNull(waitFor { session("app-1")?.takeIf { it.clients == 1 } })

        assertEquals(0, tmux(TmuxHandOffPlan.keepAlive("app-1", "/work/app/.worktrees/fix-login")))
        old.destroyForcibly().waitFor()
        val alone = waitFor { session("app-1")?.takeIf { it.clients == 0 } }
        assertEquals("the session outlived the old tab", "/work/app/.worktrees/fix-login", alone?.project)

        val new = client(prefix + TmuxHandOffPlan.attach("app-1"))
        assertNotNull("the new tab attached", waitFor { session("app-1")?.takeIf { it.clients == 1 } })
        assertEquals(0, tmux(TmuxHandOffPlan.settled("app-1")))

        new.destroyForcibly().waitFor()
        assertNotNull("settled, it ends with its last tab", waitFor { if (session("app-1") == null) true else null })
    }

    /**
     * `resize-window` pins a window to `window-size manual`, so a settled window kept the size its tab had at that moment
     * and showed tmux's dots below or beside itself once the tab grew (a maximized terminal, a wider tool window).
     */
    @Test
    fun aSettledWindowKeepsFollowingItsTabsSize() {
        start()
        val old = client(prefix + listOf("new-session", "-s", "app-1", "sleep", "60"))
        assertNotNull(waitFor { session("app-1")?.takeIf { it.clients == 1 } })
        assertEquals(0, tmux(TmuxHandOffPlan.keepAlive("app-1", "/work/app")))
        old.destroyForcibly().waitFor()
        assertNotNull(waitFor { session("app-1")?.takeIf { it.clients == 0 } })

        val tab = client(prefix + TmuxHandOffPlan.attach("app-1"), columns = 100, rows = 14)
        assertNotNull(waitFor { windowSize("app-1").takeIf { it == "100x14" } })
        assertEquals(0, tmux(TmuxHandOffPlan.settled("app-1")))

        tab.winSize = WinSize(120, 45)
        assertEquals("120x45", waitFor { windowSize("app-1").takeIf { it == "120x45" } } ?: windowSize("app-1"))
    }

    @Test
    fun aSessionNoTabAttachedToIsKeptAsABackgroundTerminal() {
        start()
        val old = client(prefix + listOf("new-session", "-s", "app-1", "sleep", "60"))
        assertNotNull(waitFor { session("app-1")?.takeIf { it.clients == 1 } })
        assertEquals(0, tmux(TmuxHandOffPlan.keepAlive("app-1", "/work/app")))
        old.destroyForcibly().waitFor()

        assertEquals(0, tmux(TmuxBackgroundPlan.keep("app-1", null)))
        assertTrue(waitFor { session("app-1")?.takeIf { it.background } } != null)
    }

    @Test
    fun aBackgroundTerminalAttachedAgainEndsWithItsTabsOnceSettled() {
        start()
        val old = client(prefix + listOf("new-session", "-s", "app-1", "sleep", "60"))
        assertNotNull(waitFor { session("app-1")?.takeIf { it.clients == 1 } })
        assertEquals(0, tmux(TmuxHandOffPlan.keepAlive("app-1", "/work/app")))
        assertEquals(0, tmux(TmuxBackgroundPlan.keep("app-1", null)))
        old.destroyForcibly().waitFor()
        assertNotNull("kept", waitFor { session("app-1")?.takeIf { it.background && it.clients == 0 } })

        val tab = client(prefix + TmuxHandOffPlan.attach("app-1"))
        assertNotNull(waitFor { session("app-1")?.takeIf { it.clients == 1 } })
        assertEquals(0, tmux(TmuxHandOffPlan.settled("app-1")))
        assertEquals("no longer a background terminal", false, session("app-1")?.background)

        tab.destroyForcibly().waitFor()
        assertNotNull("it ends with its tab", waitFor { if (session("app-1") == null) true else null })
    }

    @Test
    fun aKeptSessionOutlivesItsLastTabAsABackgroundTerminalUnderItsTabsName() {
        start()
        val tab = client(prefix + listOf("new-session", "-s", "app-1", "sleep", "60"))
        assertNotNull(waitFor { session("app-1")?.takeIf { it.clients == 1 } })

        assertEquals(0, tmux(TmuxBackgroundPlan.keep("app-1", "Local (2)")))
        tab.destroyForcibly().waitFor()
        val kept = waitFor { session("app-1")?.takeIf { it.clients == 0 } }
        assertEquals(true, kept?.background)
        assertEquals("Local (2)", kept?.tabName)

        assertEquals(0, tmux(TmuxBackgroundPlan.stop("app-1")))
        assertEquals("stopped", null, session("app-1"))
    }

    @Test
    fun anExactTargetNeverFallsThroughToALongerName() {
        start()
        client(prefix + listOf("new-session", "-s", "app-10", "sleep", "60"))
        assertNotNull(waitFor { session("app-10")?.takeIf { it.clients == 1 } })

        assertTrue("app-1 does not exist, and must not mean app-10", tmux(TmuxHandOffPlan.keepAlive("app-1", "/x")) != 0)
        assertEquals(null, session("app-10")?.project)
        assertTrue(tmux(TmuxBackgroundPlan.keep("app-1", null)) != 0)
        assertTrue(tmux(TmuxBackgroundPlan.stop("app-1")) != 0)
        assertEquals(false, session("app-10")?.background)
    }

    private fun start() {
        val binary = Tmux.find(System.getenv("PATH")) { Files.isRegularFile(it) && Files.isExecutable(it) }
        assumeNotNull(binary)
        tmux = binary
        temp = Files.createTempDirectory("tmux-handoff")
        val config = Files.writeString(temp!!.resolve("tmux.conf"), Tmux.configText(null))
        prefix = listOf(binary.toString(), "-u", "-L", socket, "-f", config.toString())
    }

    private fun client(command: List<String>, columns: Int = 80, rows: Int = 24): PtyProcess =
        PtyProcessBuilder(command.toTypedArray()).setEnvironment(System.getenv() + ("TERM" to "xterm-256color"))
            .setInitialColumns(columns).setInitialRows(rows).start().also { clients += it }

    private fun windowSize(session: String): String {
        val process = ProcessBuilder(prefix + listOf("display-message", "-p", "-t", "=$session:", "#{window_width}x#{window_height}")).redirectErrorStream(true).start()
        return process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
    }

    private fun tmux(args: List<String>): Int = ProcessBuilder(prefix + args).redirectErrorStream(true).start().let {
        it.inputStream.readBytes()
        it.waitFor()
    }

    private fun session(name: String): TmuxSession? {
        val process = ProcessBuilder(prefix + listOf("list-panes", "-a", "-F", TmuxSessions.FORMAT)).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().also { process.waitFor() }
        return TmuxSessions.parse(out).firstOrNull { it.name == name }
    }

    private fun <T : Any> waitFor(seconds: Int = 10, probe: () -> T?): T? {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(100)
        }
        return null
    }
}
