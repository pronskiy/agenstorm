package com.pronskiy.agenstorm.worktrees

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore

/**
 * Step T1.7, pure. What a project excludes so that the worktrees nested in it are not part of it: the folders that
 * hold worktrees — ours (`.worktrees`, decision 76) and Claude Code's (`.claude/worktrees`) — whole, so a worktree
 * created there later is covered without a roots change (the public way to announce one is `@Internal`); plus any
 * other linked worktree the registry knows under the project's root, picked up at the next roots change. Excluded
 * folders are also skipped when the IDE looks for VCS roots (`VcsRootScanner.visitDirsRecursivelyWithoutExcluded`),
 * so a nested worktree never becomes a second Git root of the project.
 */
object WorktreeExcludes {

    const val DEFAULT_FOLDER = ".worktrees"
    private const val CLAUDE_CODE_FOLDER = ".claude/worktrees"

    fun paths(basePath: String, folder: String, worktrees: List<Worktree>): List<String> {
        val base = basePath.trimEnd('/')
        val containers = listOf(folder, CLAUDE_CODE_FOLDER).map { "$base/${it.trim('/')}" }.distinct()
        val nested = worktrees
            .filter { !it.isMain && it.path.startsWith("$base/") }
            .map { it.path }
            .filterNot { path -> containers.any { path == it || path.startsWith("$it/") } }
        return containers + nested
    }
}

/** Step T1.7. Registered in `agenstorm-git.xml` on the project-level, dynamic `directoryIndexExcludePolicy` EP. */
class WorktreeExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {

    override fun getExcludeUrlsForProject(): Array<String> {
        val base = project.basePath?.let(FileUtil::toSystemIndependentName) ?: return emptyArray()
        val worktrees = if (project.isDisposed) emptyList() else WorktreeRegistry.getInstance(project).state.value.worktrees
        return WorktreeExcludes.paths(base, WorktreeExcludes.DEFAULT_FOLDER, worktrees).map(VfsUtilCore::pathToUrl).toTypedArray()
    }
}
