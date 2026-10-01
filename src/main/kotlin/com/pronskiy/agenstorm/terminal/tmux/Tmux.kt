package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Step U1.2. Agenstorm's own tmux: the binary, found on the login shell's PATH or where Homebrew puts it; the config,
 * written under the IDE's system path; and the commands, always on the `agenstorm` socket with that config, so the
 * user's own tmux server and `~/.tmux.conf` are never touched (decision 89). The config is the one the U0 spike settled
 * (decision 90) plus `destroy-unattached on` — a session lives as long as a tab shows it (decision 91) — and no prefix
 * key, so Ctrl+B reaches the program (Claude Code uses it). [run] blocks: call it off the EDT.
 */
@Service(Service.Level.APP)
class Tmux {

    private val configFile: Path = PathManager.getSystemDir().resolve("agenstorm").resolve("tmux.conf")

    @Volatile
    private var binary: Path? = null

    /** The tmux binary, or null when there is none (or on Windows). Looked up again until found, so an install is noticed. */
    fun binary(): Path? {
        if (SystemInfo.isWindows) return null
        binary?.let { return it }
        return find(EnvironmentUtil.getValue("PATH")) { Files.isRegularFile(it) && Files.isExecutable(it) }?.also { binary = it }
    }

    /** The full command line for tmux [args] on our socket with our config, or null when tmux is missing or the config cannot be written. */
    fun command(vararg args: String): List<String>? {
        val tmux = binary() ?: return null
        val config = config() ?: return null
        return command(tmux, config, args.toList())
    }

    /** Runs tmux [args]; its output, or null when tmux is missing, fails (no server, no such session) or takes longer than [TIMEOUT_S]. */
    fun run(vararg args: String): String? {
        val command = command(*args) ?: return null
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            when {
                !process.waitFor(TIMEOUT_S, TimeUnit.SECONDS) -> null.also { process.destroyForcibly() }
                process.exitValue() != 0 -> null
                else -> output
            }
        } catch (e: IOException) {
            LOG.debug("tmux ${args.joinToString(" ")} failed", e)
            null
        }
    }

    @Synchronized
    private fun config(): Path? = try {
        val text = configText(copyCommand(SystemInfo.isMac, System.getenv("WAYLAND_DISPLAY") != null, ::onPath))
        if (!Files.isRegularFile(configFile) || Files.readString(configFile) != text) {
            NioFiles.createDirectories(configFile.parent)
            Files.writeString(configFile, text)
        }
        configFile
    } catch (e: IOException) {
        LOG.warn("Agenstorm: could not write the tmux config to $configFile", e)
        null
    }

    private fun onPath(name: String): Boolean =
        EnvironmentUtil.getValue("PATH").orEmpty().split(File.pathSeparatorChar).any { it.isNotEmpty() && Files.isExecutable(Path.of(it, name)) }

    companion object {
        /** Agenstorm's own tmux socket (`tmux -L agenstorm`), decision 89. */
        const val SOCKET: String = "agenstorm"

        private const val TIMEOUT_S = 5L
        private val LOG = logger<Tmux>()

        /** Where Homebrew and most Linux packages put tmux, for an IDE started without the login shell's PATH. */
        val FALLBACK_DIRS: List<String> = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")

        fun getInstance(): Tmux = service()

        /** The first executable `tmux` on [path], then in [FALLBACK_DIRS]. */
        fun find(path: String?, isExecutable: (Path) -> Boolean): Path? =
            (path.orEmpty().split(File.pathSeparatorChar).filter { it.isNotEmpty() } + FALLBACK_DIRS)
                .distinct().map { Path.of(it, "tmux") }.firstOrNull(isExecutable)

        fun command(tmux: Path, config: Path, args: List<String>): List<String> =
            listOf(tmux.toString(), "-u", "-L", SOCKET, "-f", config.toString()) + args

        /**
         * What tmux's copy mode hands a selection to: the IDE's terminal ignores OSC 52, so tmux's own clipboard
         * escape cannot reach the clipboard (U0.5); a selection is piped to the platform's clipboard tool instead.
         */
        fun copyCommand(mac: Boolean, wayland: Boolean, onPath: (String) -> Boolean): String? = when {
            mac -> "pbcopy"
            wayland && onPath("wl-copy") -> "wl-copy"
            onPath("xclip") -> "xclip -selection clipboard"
            onPath("xsel") -> "xsel --clipboard --input"
            else -> null
        }

        /**
         * The config (U0's go/no-go row): no status line and no prefix key, the mouse for scrolling tmux's own history,
         * the window sized to the tab showing it, and a program's own title passed on — but not tmux's default, the
         * host name — so the IDE can show it in the tab (U1.7).
         */
        fun configText(copyCommand: String?): String = buildString {
            appendLine("# Written by Agenstorm for its tmux-backed terminal tabs. Rewritten when it differs, so edits do not stick.")
            appendLine("set -g status off")
            appendLine("set -g prefix None")
            appendLine("set -g prefix2 None")
            appendLine("set -g mouse on")
            appendLine("set -g history-limit 50000")
            appendLine("set -g window-size latest")
            appendLine("set -g aggressive-resize on")
            appendLine("set -g escape-time 0")
            appendLine("set -g focus-events on")
            appendLine("set -g destroy-unattached on")
            appendLine("set -g default-terminal \"tmux-256color\"")
            appendLine("set -as terminal-features \",xterm-256color:RGB:hyperlinks:clipboard:title\"")
            appendLine("set -g set-titles on")
            appendLine("set -g set-titles-string \"#{?#{==:#{pane_title},#{host}},,#{pane_title}}\"")
            if (copyCommand != null) appendLine("set -g copy-command \"$copyCommand\"")
        }
    }
}
