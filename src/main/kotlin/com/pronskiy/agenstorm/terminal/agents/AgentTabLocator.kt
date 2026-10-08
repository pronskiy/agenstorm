package com.pronskiy.agenstorm.terminal.agents

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.pronskiy.agenstorm.terminal.tmux.TmuxTabs
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Where a running session can be shown. */
sealed interface SessionPlace<out T> {
    /** A Terminal tab runs it. */
    data class InTab<T>(val tab: T) : SessionPlace<T>

    /** An Epic U background terminal that no tab shows: it can come back as one. */
    data class Background(val tmuxSession: String) : SessionPlace<Nothing>

    /** Outside what the IDE shows: another terminal app, the Claude desktop app, a classic-engine tab. */
    data object Elsewhere : SessionPlace<Nothing>
}

/** One Terminal tab as the locator sees it: its shell's pid once started, and the tmux session it shows, if any. */
data class TabProbe<T>(val tab: T, val shellPid: Long?, val tmuxSession: String?)

/** A tab of one project's Terminal tool window. */
data class ProjectTab(val project: Project, val tab: TerminalToolWindowTab)

/**
 * Step X2.3. Finds the tab a session runs in. A session inside tmux names its tmux session (X0.2), which is matched to
 * the tab showing it, or else to an Epic U background terminal; any other session is looked for among the processes
 * under each tab's shell. What runs nowhere the IDE can show is [SessionPlace.Elsewhere].
 */
object AgentTabLocator {

    /** Every session's place, by session id; [descendants] is asked once per started shell. */
    fun <T> locateAll(
        sessions: Collection<LiveSession>,
        tabs: List<TabProbe<T>>,
        background: Set<String>,
        descendants: (Long) -> Set<Long>,
    ): Map<String, SessionPlace<T>> {
        val trees = HashMap<Long, Set<Long>>()
        fun under(shell: Long) = trees.getOrPut(shell) { descendants(shell) }
        return sessions.associate { it.sessionId to locate(it, tabs, background, ::under) }
    }

    fun <T> locate(session: LiveSession, tabs: List<TabProbe<T>>, background: Set<String>, descendants: (Long) -> Set<Long>): SessionPlace<T> {
        session.tmuxSession?.let { name ->
            tabs.firstOrNull { it.tmuxSession == name }?.let { return SessionPlace.InTab(it.tab) }
            return if (name in background) SessionPlace.Background(name) else SessionPlace.Elsewhere
        }
        return tabs.firstOrNull { probe -> probe.tmuxSession == null && probe.shellPid?.let { session.pid in descendants(it) } == true }
            ?.let { SessionPlace.InTab(it.tab) } ?: SessionPlace.Elsewhere
    }

    /**
     * The Terminal tabs of every open project, EDT. A project whose Terminal tool window has no content yet has no tabs
     * here: asking for them would make the content and start the platform's restore of last run's tabs (Epic U).
     */
    fun probes(): List<TabProbe<ProjectTab>> = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }.flatMap { project ->
        // A project still opening or already closing may answer with an exception; it has no tabs to show then.
        runCatching {
            val window = ToolWindowManager.getInstance(project).getToolWindow(TmuxTabs.TOOL_WINDOW)
            if (window?.contentManagerIfCreated == null) return@runCatching emptyList()
            TerminalToolWindowTabsManager.getInstance(project).tabs.map { TabProbe(ProjectTab(project, it), shellPid(it), TmuxTabs.sessionOf(it)) }
        }.getOrDefault(emptyList())
    }

    /** The processes under [pid], any thread. */
    fun descendantsOf(pid: Long): Set<Long> =
        ProcessHandle.of(pid).map { handle -> handle.descendants().map { it.pid() }.toList().toSet() }.orElse(emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun shellPid(tab: TerminalToolWindowTab): Long? {
        val options = tab.view.startupOptionsDeferred
        return if (options.isCompleted && options.getCompletionExceptionOrNull() == null) options.getCompleted().pid else null
    }
}
