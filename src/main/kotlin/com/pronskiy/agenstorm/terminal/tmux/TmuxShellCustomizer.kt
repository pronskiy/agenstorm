package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.GitCommonDir
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Step U1.4 (decision 90). With `terminalTmuxEnabled` on, a new terminal tab of a Git project runs its shell inside a
 * tmux session on Agenstorm's socket, tagged with the project's repository and base path — so the session can outlive
 * the project and be handed to another window (U2). The deprecated but public `LocalTerminalCustomizer` is the one
 * public way to replace the command (`MutableShellExecOptions.setExecCommand` is internal); the platform calls it after
 * injecting shell integration and before the `ShellExecOptionsCustomizer`s, on a background thread. Only a shell is
 * wrapped, only locally, and a command that already is tmux — a hand-off tab's `attach-session` — passes untouched.
 * Registered in `agenstorm-terminal.xml`.
 */
@Suppress("DEPRECATION")
class TmuxShellCustomizer : LocalTerminalCustomizer() {

    override fun customizeCommandAndEnvironment(
        project: Project,
        workingDirectory: String?,
        command: List<String>,
        envs: MutableMap<String, String>,
        eelDescriptor: EelDescriptor,
    ): List<String> = try {
        wrap(project, workingDirectory, command, envs, eelDescriptor) ?: command
    } catch (e: Exception) {
        // A terminal must open even when tmux cannot be set up.
        LOG.warn("Agenstorm: not wrapping this terminal in tmux", e)
        command
    }

    private fun wrap(project: Project, workingDirectory: String?, command: List<String>, envs: Map<String, String>, eel: EelDescriptor): List<String>? {
        if (!AgenstormSettings.getInstance().state.terminalTmuxEnabled || SystemInfo.isWindows) return null
        if (eel != LocalEelDescriptor || project.isDisposed || !TmuxShellCommand.isShell(command)) return null
        val base = project.basePath ?: return null
        val repository = GitCommonDir.of(Path.of(base)) ?: return null
        val tmux = Tmux.getInstance()
        val prefix = tmux.command() ?: return null
        val session = nextName(Path.of(base).fileName?.toString() ?: "terminal", tmux)
        return TmuxShellCommand.wrap(prefix, session, workingDirectory, envs, command, repository.toString(), base)
    }

    @Synchronized
    private fun nextName(project: String, tmux: Tmux): String {
        val taken = TmuxSessions.read(tmux).map { it.name }.toSet() + issued.keys
        return TmuxShellCommand.sessionName(project, taken).also { issued[it] = Unit }
    }

    private companion object {
        val LOG = logger<TmuxShellCustomizer>()

        /** Names handed out this run, so two tabs opened at once do not race for one before tmux lists either. */
        val issued = ConcurrentHashMap<String, Unit>()
    }
}

/** Step U1.4, the pure part: which commands to wrap, the session's name, and the tmux command line. */
object TmuxShellCommand {

    /** Not passed on with `-e`: tmux sets these itself, or they describe the client's shell, not the session's. */
    private val NOT_PASSED = setOf("TERM", "TMUX", "TMUX_PANE", "SHLVL", "PWD", "OLDPWD", "_", "COLUMNS", "LINES")

    /** Only a shell is wrapped — not a program another plugin starts in a tab, and never tmux itself. */
    fun isShell(command: List<String>): Boolean = command.firstOrNull()?.let(TmuxSessions::isShell) == true

    /** `<project>-<n>`, the smallest `n` not [taken], in the characters tmux keeps in a session name. */
    fun sessionName(project: String, taken: Set<String>): String {
        val base = project.replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').take(32).ifEmpty { "terminal" }
        return generateSequence(1) { it + 1 }.map { "$base-$it" }.first { it !in taken }
    }

    /**
     * `tmux … new-session -s <session> -c <dir> -e K=V … -- <shell…> ; set-option @agenstorm_repo … ; set-option
     * @agenstorm_project …`. The environment goes along with `-e`: a session otherwise takes the tmux server's, frozen
     * by whichever tab started the server (U0.4) — this tab's shell integration (zsh's `ZDOTDIR`) included.
     */
    fun wrap(
        tmux: List<String>,
        session: String,
        workingDirectory: String?,
        env: Map<String, String>,
        shell: List<String>,
        repository: String,
        project: String,
    ): List<String> = buildList {
        addAll(tmux)
        addAll(listOf("new-session", "-s", session))
        if (workingDirectory != null) addAll(listOf("-c", workingDirectory))
        env.toSortedMap().forEach { (name, value) -> if (name !in NOT_PASSED) addAll(listOf("-e", "$name=$value")) }
        add("--")
        addAll(shell)
        addAll(listOf(";", "set-option", "@agenstorm_repo", repository, ";", "set-option", "@agenstorm_project", project))
    }
}
