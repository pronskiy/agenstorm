package com.pronskiy.agenstorm.tabs.offload

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.ui.TerminalWidget
import com.pronskiy.agenstorm.core.AgenstormBundle
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * Step P2.4. A terminal tab with a command running keeps its project loaded — the case this whole feature is
 * careful about, an agent working in a terminal. The terminal plugin would otherwise show its own "terminate?"
 * dialog on close. Registered only in `agenstorm-terminal.xml`, so this class is never loaded without the plugin.
 * `TerminalToolWindowManager.getTerminalWidgets()` is public; `TerminalWidget.isCommandRunning()` covers the
 * reworked terminal and `ShellTerminalWidget.hasRunningCommands()` the classic one. [widgets] is replaceable for
 * the tests.
 */
class TerminalCommandGuard(
    private val widgets: (Project) -> Collection<TerminalWidget> = { TerminalToolWindowManager.getInstance(it).terminalWidgets },
) : OffloadGuard {

    override fun busyReason(project: Project): String? =
        if (widgets(project).any(::isRunning)) AgenstormBundle.message("tabs.offload.busy.terminal") else null

    private fun isRunning(widget: TerminalWidget): Boolean {
        if (widget.isCommandRunning()) return true
        val classic = ShellTerminalWidget.asShellJediTermWidget(widget) ?: return false
        return try {
            classic.hasRunningCommands()
        } catch (e: IllegalStateException) {
            LOG.debug("Cannot tell whether a terminal has running commands; treating it as idle", e)
            false
        }
    }

    private companion object {
        val LOG = logger<TerminalCommandGuard>()
    }
}
