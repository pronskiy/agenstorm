package com.pronskiy.agenstorm.core.busy

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.ui.TerminalWidget
import com.pronskiy.agenstorm.core.AgenstormAppScope
import com.pronskiy.agenstorm.core.AgenstormBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import org.jetbrains.plugins.terminal.startup.TerminalProcessType
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalOutputStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * Step P2.4. A terminal with a command running keeps its project loaded: that is the one thing an idle rule must be
 * careful about, an agent working in a terminal. The terminal plugin would otherwise show its own "terminate?"
 * dialog on close. Registered only in `agenstorm-terminal.xml`, so this class is never loaded without the plugin.
 * `TerminalToolWindowManager.getTerminalWidgets()` is public; `TerminalWidget.isCommandRunning()` covers the
 * reworked terminal and `ShellTerminalWidget.hasRunningCommands()` the classic one. [widgets] is replaceable for
 * the tests.
 *
 * The classic check walks the process tree and asserts a background thread without read access, while the sweep
 * asks on the EDT under the write-intent lock (seen 2026-09-22 with a classic tab open: two SEVEREs blamed on the
 * plugin per sweep). So from the EDT the classic answer is the last one a background refresh gave, and *busy*
 * until there is one — a project is skipped on a guess, never forced.
 *
 * Step U2.3. `getTerminalWidgets()` lists only the tabs made through the old manager, none of the reworked terminal's
 * (seen in the U0 run), so those are asked through `TerminalToolWindowTabsManager` too: busy while shell integration
 * says a command is executing, or while a non-shell program runs in the tab. A tab whose process is a tmux client is
 * never busy: what runs lives in the tmux server and outlives the close — Epic U hands it to the next window.
 */
class TerminalCommandGuard(
    private val widgets: (Project) -> Collection<TerminalWidget> = { TerminalToolWindowManager.getInstance(it).terminalWidgets },
    private val tabs: (Project) -> Collection<TerminalToolWindowTab> = ::reworkedTabs,
    private val scope: () -> CoroutineScope = { service<AgenstormAppScope>().scope },
) : ProjectBusyGuard {

    private val classicAnswers = ConcurrentHashMap<TerminalWidget, Boolean>()

    override fun busyReason(project: Project): String? =
        if (widgets(project).any(::isRunning) || tabs(project).any(::isRunning)) AgenstormBundle.message("core.busy.terminal") else null

    private fun isRunning(tab: TerminalToolWindowTab): Boolean {
        val options = tab.view.startupOptionsDeferred.completedOrNull() ?: return false
        val status = tab.view.shellIntegrationDeferred.completedOrNull()?.outputStatus?.value
        return tabBusy(options.shellCommand, options.processType == TerminalProcessType.NON_SHELL, status == TerminalOutputStatus.ExecutingCommand)
    }

    private fun isRunning(widget: TerminalWidget): Boolean {
        // A classic widget's isCommandRunning() is hasRunningCommands() as well, so it is asked nothing on the EDT.
        val classic = ShellTerminalWidget.asShellJediTermWidget(widget) ?: return widget.isCommandRunning()
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread && !application.isReadAccessAllowed) return classicHasRunningCommands(classic)
        scope().launch(Dispatchers.IO) { classicAnswers[widget] = classicHasRunningCommands(classic) }
        return classicAnswers[widget] ?: true
    }

    private fun classicHasRunningCommands(classic: ShellTerminalWidget): Boolean =
        try {
            classic.hasRunningCommands()
        } catch (e: IllegalStateException) {
            LOG.debug("Cannot tell whether a terminal has running commands; treating it as idle", e)
            false
        }

    companion object {
        private val LOG = logger<TerminalCommandGuard>()

        /** Step U2.3: a reworked tab started with [command] — a non-shell program, or a shell executing a command — is busy, unless it is a tmux client. */
        fun tabBusy(command: List<String>, nonShell: Boolean, executing: Boolean): Boolean =
            command.firstOrNull()?.substringAfterLast('/') != "tmux" && (nonShell || executing)

        /** The reworked terminal's tabs; none while the project has no Terminal tool window, where the manager throws. */
        private fun reworkedTabs(project: Project): Collection<TerminalToolWindowTab> = try {
            TerminalToolWindowTabsManager.getInstance(project).tabs
        } catch (_: IllegalStateException) {
            emptyList()
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun <T> Deferred<T>.completedOrNull(): T? = if (isCompleted && getCompletionExceptionOrNull() == null) getCompleted() else null
    }
}
