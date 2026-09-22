package com.pronskiy.agenstorm.tabs.offload

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.ui.TerminalWidget
import com.pronskiy.agenstorm.core.AgenstormAppScope
import com.pronskiy.agenstorm.core.AgenstormBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
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
 */
class TerminalCommandGuard(
    private val widgets: (Project) -> Collection<TerminalWidget> = { TerminalToolWindowManager.getInstance(it).terminalWidgets },
    private val scope: () -> CoroutineScope = { service<AgenstormAppScope>().scope },
) : OffloadGuard {

    private val classicAnswers = ConcurrentHashMap<TerminalWidget, Boolean>()

    override fun busyReason(project: Project): String? =
        if (widgets(project).any(::isRunning)) AgenstormBundle.message("tabs.offload.busy.terminal") else null

    private fun isRunning(widget: TerminalWidget): Boolean {
        if (widget.isCommandRunning()) return true
        val classic = ShellTerminalWidget.asShellJediTermWidget(widget) ?: return false
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
    }
}
