package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.openapi.util.io.NioFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Step U1.2: finding tmux, the command line, the clipboard tool, and a config real tmux accepts. */
class TmuxTest {

    private var temp: Path? = null
    private var socket: String? = null
    private var tmux: Path? = null

    @After
    fun tearDown() {
        val binary = tmux
        val name = socket
        if (binary != null && name != null) ProcessBuilder(binary.toString(), "-L", name, "kill-server").start().waitFor()
        temp?.let(NioFiles::deleteRecursively)
    }

    @Test
    fun thePathComesFirstThenWhereHomebrewPutsIt() {
        val found = setOf(Path.of("/home/me/bin/tmux"), Path.of("/opt/homebrew/bin/tmux"))

        assertEquals(Path.of("/home/me/bin/tmux"), Tmux.find("/usr/bin/none:/home/me/bin", found::contains))
        assertEquals(Path.of("/opt/homebrew/bin/tmux"), Tmux.find("/usr/sbin", found::contains))
        assertEquals(Path.of("/opt/homebrew/bin/tmux"), Tmux.find(null, found::contains))
        assertNull(Tmux.find("/usr/sbin", { false }))
    }

    @Test
    fun everyCommandUsesOurSocketAndConfig() {
        assertEquals(
            listOf("/opt/homebrew/bin/tmux", "-u", "-L", "agenstorm", "-f", "/sys/agenstorm/tmux.conf", "attach-session", "-t", "app-1"),
            Tmux.command(Path.of("/opt/homebrew/bin/tmux"), Path.of("/sys/agenstorm/tmux.conf"), listOf("attach-session", "-t", "app-1")),
        )
    }

    @Test
    fun aSelectionGoesToThePlatformsClipboardTool() {
        assertEquals("pbcopy", Tmux.copyCommand(mac = true, wayland = false) { false })
        assertEquals("wl-copy", Tmux.copyCommand(mac = false, wayland = true) { it == "wl-copy" || it == "xclip" })
        assertEquals("xclip -selection clipboard", Tmux.copyCommand(mac = false, wayland = false) { it == "wl-copy" || it == "xclip" })
        assertEquals("xsel --clipboard --input", Tmux.copyCommand(mac = false, wayland = true) { it == "xsel" })
        assertNull(Tmux.copyCommand(mac = false, wayland = false) { false })
    }

    @Test
    fun theConfigHasNoPrefixAndEndsSessionsWithTheirLastTab() {
        val text = Tmux.configText("pbcopy")

        assertTrue(text.lines().containsAll(listOf("set -g status off", "set -g prefix None", "set -g mouse on", "set -g destroy-unattached on")))
        assertTrue(text.contains("set -g copy-command \"pbcopy\""))
        assertFalse(Tmux.configText(null).contains("copy-command"))
    }

    @Test
    fun realTmuxAcceptsTheConfig() {
        val binary = Tmux.find(System.getenv("PATH")) { Files.isRegularFile(it) && Files.isExecutable(it) }
        assumeNotNull(binary)
        tmux = binary
        temp = Files.createTempDirectory("tmux-conf")
        val config = Files.writeString(temp!!.resolve("tmux.conf"), Tmux.configText(null))
        socket = "agenstorm-test-${ProcessHandle.current().pid()}"

        fun tmux(vararg args: String): Pair<Int, String> {
            val process = ProcessBuilder(listOf(binary.toString(), "-L", socket!!, "-f", config.toString()) + args).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            return process.waitFor() to out.trim()
        }

        // One command sequence: with no session the server would not outlive a separate call.
        val (code, out) = tmux(
            "start-server", ";", "show-options", "-gv", "destroy-unattached", ";", "show-options", "-gv", "prefix",
            ";", "show-options", "-gv", "status", ";", "show-options", "-gv", "set-titles-string",
        )
        assertEquals(out, 0, code)
        assertEquals(listOf("on", "None", "off", "#{?#{==:#{pane_title},#{host}},,#{pane_title}}"), out.lines())

        // Decision 91: a session no tab shows does not stay.
        assertEquals(0, tmux("new-session", "-d", "-s", "probe", "sleep 30").first)
        assertTrue(tmux("has-session", "-t", "probe").first != 0)
    }
}
