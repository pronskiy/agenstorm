package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindowManager
import com.pronskiy.agenstorm.core.AgenstormSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Step U3.1 (decision 91). When a project opens, each background terminal kept from it — on a close or a quit (U3.2) —
 * is a tab of its window again. The platform restores the last run's tabs by itself once the Terminal tool window's
 * content is made, and a hand-off's attach tab among them re-attaches with no help; so the restore is set off first and
 * given time, and only a session no tab shows by then gets a tab of its own. A restore slower than that is caught by
 * [TmuxTabsListener], which closes a second tab attached to one session, and whichever tab attaches to a background
 * terminal turns it back into an ordinary session there. Matched on the project, not the repository: two worktrees of
 * one repository reopening after a quit each get their own back, and the others stay listed (U3.3).
 */
class TmuxReattach : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!AgenstormSettings.getInstance().state.terminalTmuxEnabled || SystemInfo.isWindows) return
        val base = project.basePath ?: return
        val tmux = Tmux.getInstance()
        val kept = withContext(Dispatchers.IO) { if (tmux.binary() == null) emptyList() else TmuxSessions.read(tmux) }
        if (TmuxReattachPlan.toOpen(kept, base, emptySet()).isEmpty()) return
        withTimeoutOrNull(REGISTRATION) {
            while (withContext(Dispatchers.EDT) { ToolWindowManager.getInstance(project).getToolWindow(TmuxTabs.TOOL_WINDOW) } == null) delay(POLL)
        } ?: return
        // Asking for the tabs makes the tool window's content, which sets the platform's restore off.
        withContext(Dispatchers.EDT) { if (!project.isDisposed) TmuxTabs.of(project) }
        delay(RESTORE)
        val sessions = withContext(Dispatchers.IO) { TmuxSessions.read(tmux) }
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            val open = TmuxReattachPlan.toOpen(sessions, base, TmuxTabs.of(project).map { it.second }.toSet())
            open.forEach { TmuxTabs.openAttached(project, it.name, it.tabName ?: it.label, rename = null, focus = false) }
            if (open.isEmpty()) return@withContext
            TmuxTabs.showToolWindow(project, focus = false)
            LOG.info("Agenstorm: background terminals ${open.joinToString { it.name }} back in ${project.name}")
        }
    }

    private companion object {
        val LOG = logger<TmuxReattach>()
        val REGISTRATION = 10.seconds
        val RESTORE = 2.seconds
        val POLL = 200.milliseconds
    }
}

/** Step U3.1, the pure part. */
object TmuxReattachPlan {

    /** The background terminals kept from [project] (its base path) that no client shows and no tab of it already asks for. */
    fun toOpen(sessions: List<TmuxSession>, project: String, shown: Set<String>): List<TmuxSession> =
        sessions.filter { it.background && it.clients == 0 && it.project == project && it.name !in shown }

    /** Whether a tab asking for [session] is a second one: another tab of the window already shows it (U3.1). */
    fun isDuplicate(session: String?, others: List<String?>): Boolean = session != null && session in others
}
