package com.pronskiy.agenstorm.worktrees.setup

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import java.nio.file.Path

/**
 * Step T2.5 (decision 84). The setup in a tab of the reworked terminal, through its `@Experimental` tab builder
 * (`workingDirectory`, `envVariables`, `tabName` — none of them `@Internal`, 262 and 263) and the view's
 * `createSendTextBuilder().shouldExecute()`. The shell only starts once the Terminal tool window is showing, so it is
 * shown. The tab stays afterwards, and while the command runs `TerminalCommandGuard` keeps the worktree open through a
 * switch. Registered in `agenstorm-terminal.xml`; any failure falls back to the Run tool window.
 */
class ReworkedSetupTerminal : SetupTerminal {

    override fun run(project: Project, directory: Path, env: Map<String, String>, tabName: String, command: String): Boolean = try {
        val tab = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
            .workingDirectory(directory.toString())
            .envVariables(env)
            .tabName(tabName)
            .requestFocus(true)
            .deferSessionStartUntilUiShown(false)
            .createTab()
        ToolWindowManager.getInstance(project).getToolWindow(TERMINAL)?.activate(null)
        tab.view.createSendTextBuilder().shouldExecute().send(command)
        true
    } catch (e: Exception) {
        LOG.info("Could not start the setup in a terminal tab; using the Run tool window", e)
        false
    } catch (e: LinkageError) {
        LOG.info("The reworked terminal is not available; using the Run tool window", e)
        false
    }

    private companion object {
        val LOG = logger<ReworkedSetupTerminal>()
        const val TERMINAL = "Terminal"
    }
}
