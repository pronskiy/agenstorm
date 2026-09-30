package com.pronskiy.agenstorm.worktrees.carry

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.nio.file.Files
import java.nio.file.Path

/**
 * Step T2.2. Runs [CarryPlan] against the main checkout and clones what it picks into the new worktree. [lsFiles] runs
 * `git ls-files` with the given arguments in the main checkout and returns its output, or null when git failed.
 */
class CarryOver(
    private val lsFiles: (List<String>) -> List<String>?,
    private val cloner: TreeCloner = TreeCloner(),
) {

    /** Returns the relative paths that were copied. */
    fun run(main: Path, worktree: Path, skip: List<String>): List<String> {
        val include = main.resolve(CarryPlan.INCLUDE_FILE).takeIf(Files::isRegularFile)
        val candidates = lsFiles(CarryPlan.candidateArgs(include))?.let(CarryPlan::split) ?: return emptyList()
        if (candidates.isEmpty()) return emptyList()
        val ignored = lsFiles(CarryPlan.IGNORED_ARGS)?.let(CarryPlan::split) ?: return emptyList()
        val copied = CarryPlan.entries(candidates, ignored, skip).filter { cloner.clone(main.resolve(it), worktree.resolve(it)) }
        LOG.info("Carried ${copied.size} entries into $worktree: ${copied.joinToString()}")
        return copied
    }

    companion object {
        private val LOG = logger<CarryOver>()

        /** `git ls-files` through git4idea's public `GitLineHandler`, in [main]. Blocking. */
        fun gitLsFiles(project: Project, main: Path): (List<String>) -> List<String>? = { args ->
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(main)?.let { root ->
                val handler = GitLineHandler(project, root, GitCommand.LS_FILES)
                handler.setSilent(true)
                handler.addParameters(args)
                val result = Git.getInstance().runCommand(handler)
                if (result.success()) result.output else null.also { LOG.info("git ls-files failed in $main: ${result.errorOutputAsJoinedString}") }
            }
        }
    }
}
