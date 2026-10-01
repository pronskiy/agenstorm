package com.pronskiy.agenstorm.terminal.tmux

/** One pane of a tmux session: its shell's pid, what runs in its foreground, where, and the title a program gave it. */
data class TmuxPane(val pid: Long, val command: String, val path: String, val title: String?) {
    /** A program other than the shell is in the foreground — an agent, a dev server, a test run. */
    val running: Boolean get() = !TmuxSessions.isShell(command)
}

/**
 * One session on Agenstorm's socket. [repository] (the common git dir, U1.1) and [project] (the base path) are what
 * the session was started for, stored as the session options `@agenstorm_repo` and `@agenstorm_project`; [background]
 * marks one kept running past its last tab (`@agenstorm_background`, decision 91).
 */
data class TmuxSession(
    val name: String,
    val clients: Int,
    val createdEpochSeconds: Long,
    val repository: String?,
    val project: String?,
    val background: Boolean,
    val panes: List<TmuxPane>,
) {
    val running: Boolean get() = panes.any { it.running }

    /** What to call it: the running program's own title (Claude's `✳ Claude Code`), else its command, else the shell's. */
    val label: String
        get() {
            val pane = panes.firstOrNull { it.running } ?: panes.firstOrNull() ?: return name
            return pane.title.takeIf { pane.running } ?: pane.command
        }
}

/**
 * Step U1.3. Reads the sessions on Agenstorm's socket from one `list-panes -a -F` call ([FORMAT]): a line per pane,
 * fields split by the ASCII unit separator, which no name, path or title carries. The host name rides along so that
 * tmux's default pane title — the host — is not mistaken for one a program set. Pure: [Tmux] runs the command.
 */
object TmuxSessions {

    private const val SEP = "\u001f"

    private val FIELDS = listOf(
        "#{session_name}", "#{session_attached}", "#{session_created}",
        "#{@agenstorm_repo}", "#{@agenstorm_project}", "#{@agenstorm_background}",
        "#{pane_pid}", "#{pane_current_command}", "#{pane_current_path}", "#{pane_title}", "#{host}",
    )

    /** The `-F` argument of `list-panes -a`. */
    val FORMAT: String = FIELDS.joinToString(SEP)

    /** Shells, as tmux reports a pane's foreground command; a login shell shows up with a leading `-`. */
    private val SHELLS = setOf("sh", "bash", "zsh", "fish", "dash", "ksh", "mksh", "tcsh", "csh", "nu", "elvish", "xonsh", "login")

    fun isShell(command: String): Boolean = command.removePrefix("-").substringAfterLast('/') in SHELLS

    /** Sessions in the order tmux lists them, each with its panes; lines that do not carry every field are skipped. */
    fun parse(output: String): List<TmuxSession> {
        val sessions = LinkedHashMap<String, TmuxSession>()
        for (line in output.lineSequence()) {
            val f = line.split(SEP)
            if (f.size != FIELDS.size || f[0].isEmpty()) continue
            val title = f[9].takeIf { it.isNotBlank() && it != f[10] }
            val pane = TmuxPane(f[6].toLongOrNull() ?: continue, f[7], f[8], title)
            val session = sessions[f[0]] ?: TmuxSession(
                name = f[0],
                clients = f[1].toIntOrNull() ?: 0,
                createdEpochSeconds = f[2].toLongOrNull() ?: 0,
                repository = f[3].ifEmpty { null },
                project = f[4].ifEmpty { null },
                background = f[5] == "1",
                panes = emptyList(),
            )
            sessions[f[0]] = session.copy(panes = session.panes + pane)
        }
        return sessions.values.toList()
    }

    /** The sessions on Agenstorm's socket now; none when tmux is missing or no server runs. Blocking: off the EDT. */
    fun read(tmux: Tmux = Tmux.getInstance()): List<TmuxSession> =
        tmux.run("list-panes", "-a", "-F", FORMAT)?.let(::parse).orEmpty()
}
