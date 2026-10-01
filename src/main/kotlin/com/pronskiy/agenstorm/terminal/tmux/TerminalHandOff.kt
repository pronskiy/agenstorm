package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.pronskiy.agenstorm.core.AgenstormAppScope
import com.pronskiy.agenstorm.core.GitCommonDir
import com.pronskiy.agenstorm.core.ProjectHandOff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Step U2.2 (decisions 88, 91, 92). On a worktree switch ([ProjectHandOff]), each tmux session of the closing project's
 * tabs that runs something becomes a tab of the new project's window, attached to the same session — same process,
 * output going on. Before the old project closes, the session is told not to end with its last client
 * (`destroy-unattached off`) and is re-tagged with the new project, so the moment between the old tab's client leaving
 * and the new one arriving costs nothing; once a client of the new tab is there, it ends with its tabs again. Idle
 * sessions are left alone: their tabs close with the project and `destroy-unattached` ends them. The new tab runs
 * `tmux attach-session` as an ordinary shell tab — a non-shell one makes the platform's project-close check ask to
 * terminate it on the next switch (U2 guardrail run), while [TmuxTitleMirror] shows the program's title either way —
 * keeps a rename, and starts only once shown, at the size it is shown at. A session the new window never attaches before it
 * closes is kept as a background terminal (`@agenstorm_background`, U3) rather than lost.
 */
class TerminalHandOff : ProjectHandOff {

    override suspend fun handOff(from: Project, to: Project) {
        val tabs = withContext(Dispatchers.EDT) { tabsOf(from) }
        LOG.debug { "hand-off ${from.name} -> ${to.name}: tmux tabs ${tabs.map { it.session }}" }
        if (tabs.isEmpty()) return
        val tmux = Tmux.getInstance()
        val repository = to.basePath?.let { GitCommonDir.of(Path.of(it))?.toString() }
        val toBase = to.basePath ?: return
        val moves = withContext(Dispatchers.IO) {
            TmuxHandOffPlan.plan(tabs, TmuxSessions.read(tmux), repository).onEach { move ->
                if (tmux.run(*TmuxHandOffPlan.keepAlive(move.session, toBase).toTypedArray()) == null) LOG.warn("Agenstorm: could not keep ${move.session} alive through the hand-off")
            }
        }
        LOG.debug { "hand-off ${from.name} -> ${to.name}: moving ${moves.map { it.session }} (repository $repository)" }
        if (moves.isEmpty()) return
        val opened = withContext(Dispatchers.EDT) {
            val opened = moves.mapIndexedNotNull { index, move ->
                TmuxTabs.openAttached(to, move.session, move.name, move.userDefinedTitle, focus = index == 0)?.let { move.session to it }
            }
            if (opened.isNotEmpty()) TmuxTabs.showToolWindow(to, focus = true)
            opened
        }
        LOG.info("Agenstorm: handed ${moves.joinToString { it.session }} from ${from.name} to ${to.name}")
        service<AgenstormAppScope>().scope.launch { settle(to, opened.toMap(), moves.map { it.session }) }
    }

    private fun tabsOf(project: Project): List<TmuxHandOffPlan.Tab> =
        TmuxTabs.of(project).map { (tab, session) ->
            TmuxHandOffPlan.Tab(session, tab.view.title.userDefinedTitle, tab.content.getUserData(TmuxTitleMirror.ORIGINAL_NAME) ?: tab.content.displayName)
        }

    /**
     * Turns `destroy-unattached` back on for each moved session once a client is attached — its new tab started — and
     * fits the window to that tab. A session whose tab closed unstarted, or whose window closed first, becomes a
     * background terminal instead of a leftover nobody sees.
     */
    private suspend fun settle(to: Project, tabs: Map<String, TerminalToolWindowTab>, sessions: List<String>) {
        val tmux = Tmux.getInstance()
        val waiting = sessions.toMutableSet()
        while (waiting.isNotEmpty()) {
            delay(POLL)
            val now = withContext(Dispatchers.IO) { TmuxSessions.read(tmux).associateBy { it.name } }
            val alive = !to.isDisposed
            val shown = if (alive) withContext(Dispatchers.EDT) { TerminalToolWindowTabsManager.getInstance(to).tabs.toSet() } else emptySet()
            for (name in waiting.toList()) {
                val session = now[name]
                when {
                    session == null -> waiting -= name
                    session.clients > 0 -> {
                        withContext(Dispatchers.IO) { tmux.run(*TmuxHandOffPlan.settled(name).toTypedArray()) }
                        waiting -= name
                    }
                    !alive || tabs[name] !in shown -> {
                        withContext(Dispatchers.IO) { tmux.run(*TmuxHandOffPlan.keptInBackground(name).toTypedArray()) }
                        waiting -= name
                    }
                }
            }
        }
    }

    private companion object {
        val LOG = logger<TerminalHandOff>()
        val POLL = 1.seconds
    }
}

/** Step U2.2, the pure part: which of the closing project's tmux sessions move, once each, and what the new tab is called. */
object TmuxHandOffPlan {

    /** A tab of the closing project showing [session]; [userDefinedTitle] is a rename, [name] the tab's own name. */
    data class Tab(val session: String, val userDefinedTitle: String?, val name: String)

    data class Move(val session: String, val name: String, val userDefinedTitle: String?)

    /**
     * The sessions of [tabs] that run something, in tab order, each once, and only of [repository] (decision 89) when it
     * is known. The new tab keeps a rename; otherwise it takes the old tab's own name — not the program's title, which
     * the IDE shows anyway while it runs — or, with neither, what runs.
     */
    /**
     * Targets name a session exactly — `app-1` must never fall through to `app-10` — and tmux spells that differently
     * per command: `=app-1` where it wants a session (`attach-session`), `=app-1:` where it wants a pane or window
     * (`set-option`, `resize-window`), which rejects the bare `=app-1` (found in the U2 guardrail run: the keep-alive
     * failed silently and the moving sessions died with the old project).
     */
    fun attach(session: String): List<String> = listOf("attach-session", "-t", "=$session")

    /** Before the old project closes: the session outlives its clients for now, and belongs to the new project. */
    fun keepAlive(session: String, project: String): List<String> =
        listOf("set-option", "-t", "=$session:", "destroy-unattached", "off", ";", "set-option", "-t", "=$session:", "@agenstorm_project", project)

    /**
     * Once a tab is attached: the session ends with its tabs again — no longer a background terminal, if it was one
     * (U3.1) — and fills the tab. In this order, since tmux stops a `;` chain at the first error.
     */
    fun settled(session: String): List<String> = listOf(
        "set-option", "-t", "=$session:", "destroy-unattached", "on", ";",
        "set-option", "-u", "-t", "=$session:", "@agenstorm_background", ";",
        "resize-window", "-A", "-t", "=$session:",
    )

    /** A session no new tab ever attached to: kept, and listed as a background terminal (U3). */
    fun keptInBackground(session: String): List<String> = listOf("set-option", "-t", "=$session:", "@agenstorm_background", "1")

    fun plan(tabs: List<Tab>, sessions: List<TmuxSession>, repository: String?): List<Move> {
        val byName = sessions.associateBy { it.name }
        return tabs.distinctBy { it.session }.mapNotNull { tab ->
            val session = byName[tab.session]?.takeIf { it.running } ?: return@mapNotNull null
            if (repository != null && session.repository != null && session.repository != repository) return@mapNotNull null
            Move(tab.session, tab.userDefinedTitle ?: tab.name.ifBlank { session.label }, tab.userDefinedTitle)
        }
    }
}
