package com.pronskiy.agenstorm.worktrees.setup

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.pronskiy.agenstorm.core.AgenstormBundle
import java.nio.file.Files
import java.nio.file.Path

/**
 * Step T2.5 (decision 84). Runs a worktree's setup in the project it belongs to: in a tab of the reworked terminal when
 * the Terminal plugin provides one ([SetupTerminal]) — the tab stays for whoever works there next — and otherwise in the
 * Run tool window, with output and a stop button. Either way something is running while it runs, so the busy guards
 * keep the worktree open through a switch. The setup gets Cursor's `ROOT_WORKTREE_PATH` (the main checkout) and
 * `AGENSTORM_WORKTREE_PATH` / `AGENSTORM_WORKTREE_NAME`, and runs in the worktree.
 */
object SetupRunner {

    private val LOG = logger<SetupRunner>()

    /** Whether the main checkout asks for a setup at all. */
    fun hasSetup(main: Path): Boolean = load(main) != null

    /** Runs the setup of [main] in [worktree]; false when there is none. Call on the EDT. */
    fun run(project: Project, main: Path, worktree: Path): Boolean {
        val setup = load(main) ?: return false
        val name = worktree.fileName.toString()
        val title = AgenstormBundle.message("worktrees.setup.title", name)
        val terminal = SetupTerminal.EP_NAME.extensionList.firstOrNull()
        if (terminal != null && terminal.run(project, worktree, environment(main, worktree), title, shellLine(setup, SystemInfo.isWindows))) return true
        return try {
            val handler = KillableColoredProcessHandler(commandLine(setup, main, worktree, SystemInfo.isWindows))
            RunContentExecutor(project, handler)
                .withTitle(title)
                .withActivateToolWindow(true)
                .run()
            true
        } catch (e: Exception) {
            LOG.warn("Could not start the setup of $name", e)
            false
        }
    }

    fun commandLine(setup: SetupConfig.Setup, main: Path, worktree: Path, windows: Boolean): GeneralCommandLine {
        val command = when (setup) {
            is SetupConfig.Setup.Commands ->
                if (windows) listOf("cmd.exe", "/c", setup.commands.joinToString(" && "))
                else listOf("/bin/sh", "-c", (listOf("set -e") + setup.commands).joinToString("\n"))
            is SetupConfig.Setup.Script ->
                if (windows) listOf("cmd.exe", "/c", setup.path.toString()) else listOf("/bin/sh", setup.path.toString())
        }
        return GeneralCommandLine(command)
            .withWorkingDirectory(worktree)
            .withEnvironment(environment(main, worktree))
    }

    fun environment(main: Path, worktree: Path): Map<String, String> = mapOf(
        "ROOT_WORKTREE_PATH" to main.toString(),
        "AGENSTORM_WORKTREE_PATH" to worktree.toString(),
        "AGENSTORM_WORKTREE_NAME" to worktree.fileName.toString(),
    )

    /**
     * The line typed into an interactive shell: the commands joined with `&&`, so the first failure stops the rest
     * without ending the shell the way `set -e` would; a script goes to `sh`.
     */
    fun shellLine(setup: SetupConfig.Setup, windows: Boolean): String = when (setup) {
        is SetupConfig.Setup.Commands -> setup.commands.joinToString(" && ")
        is SetupConfig.Setup.Script -> if (windows) "\"${setup.path}\"" else "sh ${quote(setup.path.toString())}"
    }

    private fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    private fun load(main: Path): SetupConfig.Setup? {
        val file = SetupConfig.find(main) ?: return null
        return try {
            SetupConfig.parse(Files.readString(file), file.parent, SystemInfo.isWindows)
        } catch (e: Exception) {
            LOG.info("Could not read $file", e)
            null
        }
    }
}
