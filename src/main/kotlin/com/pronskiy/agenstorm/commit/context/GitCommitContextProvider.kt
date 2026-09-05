package com.pronskiy.agenstorm.commit.context

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import git4idea.repo.GitRepositoryManager

/**
 * Branch name through Git4Idea. Lives in `agenstorm-git.xml`, so it is only loaded when the Git plugin is
 * present; without it `{branch}` renders as "(unknown)". Prefers the repository containing the project directory.
 */
class GitCommitContextProvider : CommitContextProvider {

    override fun branchName(project: Project): String? {
        val repositories = GitRepositoryManager.getInstance(project).repositories
        val projectDir = project.guessProjectDir()
        val preferred = projectDir?.let { dir -> repositories.firstOrNull { VfsUtilCore.isAncestor(it.root, dir, false) } }
        return (preferred ?: repositories.firstOrNull())?.currentBranch?.name
    }
}
