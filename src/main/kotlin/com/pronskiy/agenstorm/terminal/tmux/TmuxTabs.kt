package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * The Terminal tool window's tabs as Epic U sees them: which of our tmux sessions a tab shows, and a new tab attached to
 * one — what a worktree switch (U2.2), a project reopening (U3.1) and the background terminals' *Open* (U3.3) share.
 * Call on the EDT.
 */
object TmuxTabs {

    const val TOOL_WINDOW: String = "Terminal"

    /**
     * The session [tab] shows: the one its started command names, or — for a tab not started yet, a moved tab nobody
     * has looked at — the one it asked for.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun sessionOf(tab: TerminalToolWindowTab): String? {
        val options = tab.view.startupOptionsDeferred
        val command = if (options.isCompleted && options.getCompletionExceptionOrNull() == null) options.getCompleted().shellCommand else tab.processOptions.shellCommand
        return command?.let(TmuxShellCommand::sessionOf)
    }

    /** The tabs of [project] that show one of our sessions; none while it has no Terminal tool window, where the manager throws. */
    fun of(project: Project): List<Pair<TerminalToolWindowTab, String>> = try {
        TerminalToolWindowTabsManager.getInstance(project).tabs.mapNotNull { tab -> sessionOf(tab)?.let { tab to it } }
    } catch (_: IllegalStateException) {
        emptyList()
    }

    /**
     * A tab of [project] running `tmux attach-session -t =<session>`: an ordinary shell tab to the IDE (a non-shell one
     * makes the platform's project-close check ask, U2 guardrail run), started only once shown — at the size it is shown
     * at — and keeping a rename.
     */
    fun openAttached(project: Project, session: String, name: String, rename: String?, focus: Boolean): TerminalToolWindowTab? {
        val command = Tmux.getInstance().command(*TmuxHandOffPlan.attach(session).toTypedArray()) ?: return null
        val tab = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
            .shellCommand(command)
            .tabName(name)
            .requestFocus(focus)
            .deferSessionStartUntilUiShown(true)
            .createTab()
        rename?.let { tab.view.title.change { userDefinedTitle = it } }
        return tab
    }

    /** Brings the Terminal tool window up, so the selected new tab starts; [focus] moves the keyboard there too. */
    fun showToolWindow(project: Project, focus: Boolean) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW) ?: return
        if (focus) window.activate(null) else window.show()
    }
}
