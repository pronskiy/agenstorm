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
 * Step T2.5. Runs a worktree's setup in the Run tool window of the project it belongs to (decision 84): output stays
 * visible and the run can be stopped, and while it runs `RunningProcessesGuard` counts the worktree as busy, so no
 * switch closes it halfway. The script gets Cursor's `ROOT_WORKTREE_PATH` (the main checkout) and
 * `AGENSTORM_WORKTREE_PATH` / `AGENSTORM_WORKTREE_NAME`, runs in the worktree, and sees the environment a terminal would.
 */
object SetupRunner {

    private val LOG = logger<SetupRunner>()

    /** Whether the main checkout asks for a setup at all. */
    fun hasSetup(main: Path): Boolean = load(main) != null

    /** Runs the setup of [main] in [worktree]; false when there is none. Call on the EDT. */
    fun run(project: Project, main: Path, worktree: Path): Boolean {
        val setup = load(main) ?: return false
        val name = worktree.fileName.toString()
        return try {
            val handler = KillableColoredProcessHandler(commandLine(setup, main, worktree, SystemInfo.isWindows))
            RunContentExecutor(project, handler)
                .withTitle(AgenstormBundle.message("worktrees.setup.title", name))
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
            .withEnvironment(
                mapOf(
                    "ROOT_WORKTREE_PATH" to main.toString(),
                    "AGENSTORM_WORKTREE_PATH" to worktree.toString(),
                    "AGENSTORM_WORKTREE_NAME" to worktree.fileName.toString(),
                ),
            )
    }

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
