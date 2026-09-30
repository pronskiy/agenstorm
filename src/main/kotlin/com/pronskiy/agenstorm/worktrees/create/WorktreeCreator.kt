package com.pronskiy.agenstorm.worktrees.create

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.worktrees.WorktreeExcludes
import com.pronskiy.agenstorm.worktrees.WorktreeRegistry
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Step T2.1 (minimal). Makes a worktree the way decision 76 says: `<main checkout>/.worktrees/<slug>` on a new branch
 * `<slug>`, through `git worktree add` run by git4idea's public `GitLineHandler` (`Git.createWorkingTree` changed its
 * signature between 262 and 263, decision 78). `--no-track` keeps a branch made from the default branch from
 * tracking `origin/…`. The folder is ignored through `<common git dir>/info/exclude`, so no tracked file changes, and the
 * base is recorded in `branch.<slug>.agenstormBase` for T4's merge back. Blocking: call it off the EDT.
 */
class WorktreeCreator(private val project: Project, private val repository: GitRepository) {

    sealed interface Result {
        data class Created(val path: String, val main: String) : Result
        data class Failed(val reason: String) : Result
    }

    /** [base] is what `git worktree add` starts from (`HEAD`, `origin/HEAD`); [baseName] is what gets recorded. */
    fun create(slug: String, base: String, baseName: String): Result {
        val snapshot = WorktreeRegistry.getInstance(project).state.value
        val main = snapshot.worktrees.firstOrNull { it.isMain }?.path ?: FileUtil.toSystemIndependentName(repository.root.path)
        val commonDir = snapshot.commonDir ?: FileUtil.toSystemIndependentName(repository.repositoryFiles.worktreesDirFile.parent)
        val folder = WorktreeExcludes.configuredFolder()
        val target = "$main/$folder/$slug"
        if (Files.exists(Path.of(target))) return Result.Failed(AgenstormBundle.message("worktrees.new.error.folder", target))

        val add = GitLineHandler(project, repository.root, GitCommand.WORKTREE)
        add.addParameters("add", "--no-track", "-b", slug, target, base)
        val added = Git.getInstance().runCommand(add)
        if (!added.success()) return Result.Failed(added.errorOutputAsJoinedString)

        ignore(Path.of(commonDir, "info", "exclude"), "/$folder/")
        val config = GitLineHandler(project, repository.root, GitCommand.CONFIG)
        config.addParameters("branch.$slug.agenstormBase", baseName)
        val recorded = Git.getInstance().runCommand(config)
        if (!recorded.success()) LOG.warn("Could not record the base of $slug: ${recorded.errorOutputAsJoinedString}")

        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(target))
        WorktreeRegistry.getInstance(project).refresh()
        return Result.Created(target, main)
    }

    /** The default branch's remote ref (`origin/main`), or null when the repository has no `origin/HEAD`. */
    fun defaultBranch(): String? {
        val parse = GitLineHandler(project, repository.root, GitCommand.REV_PARSE)
        parse.addParameters("--abbrev-ref", "origin/HEAD")
        parse.setSilent(true)
        val result = Git.getInstance().runCommand(parse)
        return result.output.firstOrNull()?.trim()?.takeIf { result.success() && it.isNotEmpty() && it != "origin/HEAD" }
    }

    private fun ignore(exclude: Path, entry: String) {
        try {
            val current = if (Files.exists(exclude)) Files.readString(exclude) else null
            val next = ExcludeFile.withEntry(current, entry) ?: return
            Files.createDirectories(exclude.parent)
            Files.writeString(exclude, next)
        } catch (e: IOException) {
            LOG.warn("Could not add $entry to $exclude", e)
        }
    }

    private companion object {
        val LOG = logger<WorktreeCreator>()
    }
}
