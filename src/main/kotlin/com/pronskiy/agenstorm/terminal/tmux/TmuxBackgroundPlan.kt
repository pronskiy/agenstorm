package com.pronskiy.agenstorm.terminal.tmux

/**
 * Steps U3.2–U3.4, the pure part of background terminals (decision 91): which sessions a close or a quit puts at stake,
 * and the tmux commands that keep one or stop it. Targets are exact, as in [TmuxHandOffPlan]: `=name:` for an option,
 * `=name` for a session.
 */
object TmuxBackgroundPlan {

    /** A tab showing [session]: [title] is what the tab reads now, [name] what a tab showing it again is called. */
    data class Shown(val session: String, val title: String, val name: String)

    /** A session a close or a quit decided about, and the name of the tab it had. */
    data class Decided(val session: String, val tabName: String?)

    /** Kept running past its last tab, as a background terminal called what its tab was called (U3.1 reads it back). */
    fun keep(session: String, tabName: String?): List<String> = buildList {
        addAll(listOf("set-option", "-t", "=$session:", "destroy-unattached", "off", ";", "set-option", "-t", "=$session:", "@agenstorm_background", "1"))
        if (!tabName.isNullOrBlank()) addAll(listOf(";", "set-option", "-t", "=$session:", "@agenstorm_name", tabName))
    }

    fun stop(session: String): List<String> = listOf("kill-session", "-t", "=$session")

    /**
     * The sessions that closing the tabs in [shown] would end: running something, with no client beyond those tabs (one
     * shown in another window, or attached from outside the IDE, goes on anyway), and — for one project's close, [project]
     * its base path — still that project's: a worktree switch has already handed its running sessions to the next window
     * and tagged them with it (U2.2). [project] is null for a quit, where every window closes.
     */
    fun atStake(sessions: List<TmuxSession>, shown: List<Shown>, project: String?): List<TmuxSession> {
        val tabs = shown.groupingBy { it.session }.eachCount()
        return sessions.filter { session ->
            val count = tabs[session.name] ?: return@filter false
            session.running && session.clients <= count && (project == null || session.project == null || session.project == project)
        }
    }

    /** Step U3.3: the background terminals to list — kept, and shown by no tab — oldest first. */
    fun listed(sessions: List<TmuxSession>): List<TmuxSession> =
        sessions.filter { it.background && it.clients == 0 }.sortedBy { it.createdEpochSeconds }

    enum class OpenIn { THIS_WINDOW, ITS_PROJECT }

    /**
     * Where *Open* puts [session]: this window when it is a worktree of this window's [repository] — any worktree's
     * terminal can run in any of them — or when the folder it came from is gone; otherwise its own project's window.
     */
    fun openIn(session: TmuxSession, repository: String?, folderExists: Boolean): OpenIn =
        if (!folderExists || session.repository == null || session.repository == repository) OpenIn.THIS_WINDOW else OpenIn.ITS_PROJECT

    /** Opened in another worktree's window, a session belongs to that worktree from then on (U3.1, U3.2 read the tag). */
    fun adopt(session: String, project: String): List<String> = listOf("set-option", "-t", "=$session:", "@agenstorm_project", project)

    /** The folder a session belongs to, as the quit prompt and the list name it: `fix-login` for `/work/app/.worktrees/fix-login`. */
    fun worktreeName(session: TmuxSession): String? = session.project?.trimEnd('/')?.substringAfterLast('/')?.ifEmpty { null }
}
