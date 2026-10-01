package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.execution.ui.BaseContentCloseListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.terminal.frontend.toolwindow.TerminalTabsManagerListener
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.ui.content.Content
import com.pronskiy.agenstorm.core.AgenstormBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Step U1.6 (decision 92). ✕ on a tmux tab whose session runs something asks first, as it does for a plain tab. The
 * platform's own check (`TerminalTabCloseListener`, internal) looks for the tab process's children, and a tmux client
 * has none, so this listener — on the same public base, `BaseContentCloseListener` — asks tmux about [session] instead.
 * Only the session's last tab asks: closing one of two windows showing it ends nothing. *Terminate* lets the tab close,
 * and with its client gone `destroy-unattached` ends the session.
 *
 * Project close and quit are not its business (U3.2): the base class would veto them per tab and, when it does not,
 * remove the tab at once — before another listener might still cancel the close — so [canClose] stays out.
 */
class TmuxTabCloseListener(content: Content, project: Project, private val session: String) :
    BaseContentCloseListener(content, project, content) {

    override fun closeQuery(content: Content, projectClosing: Boolean): Boolean {
        if (projectClosing || myProject.isDisposed || ApplicationManager.getApplication().isExitInProgress) return true
        if (content.getUserData(Content.TEMPORARY_REMOVED_KEY) == true) return true
        val state = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<TmuxSession?, RuntimeException> { TmuxSessions.read().firstOrNull { it.name == session } },
            AgenstormBundle.message("terminal.tmux.checking"), true, myProject,
        ) ?: return true
        if (!state.running || state.clients > 1) return true
        return TmuxCloseDialog.ask(myProject, listOf(content.displayName), canKeep = false) == TmuxCloseDialog.Answer.TERMINATE
    }

    override fun canClose(project: Project): Boolean = true

    override fun disposeContent(content: Content) = Unit
}

/**
 * Puts a [TmuxTabCloseListener] and a [TmuxTitleMirror] on each terminal tab whose shell turns out to run in one of our
 * tmux sessions — known only once the session has started, since a *New Tab* tab is wrapped on its way to the shell
 * (U1.4). Registered as a project listener in `agenstorm-terminal.xml`.
 */
class TmuxTabsListener(private val project: Project) : TerminalTabsManagerListener {

    override fun tabAdded(tab: TerminalToolWindowTab) {
        val view = tab.view
        view.coroutineScope.launch {
            val session = TmuxShellCommand.sessionOf(view.startupOptionsDeferred.await().shellCommand) ?: return@launch
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                TmuxTabCloseListener(tab.content, project, session)
                TmuxTitleMirror(view, session, tab.content).start()
            }
        }
    }
}
