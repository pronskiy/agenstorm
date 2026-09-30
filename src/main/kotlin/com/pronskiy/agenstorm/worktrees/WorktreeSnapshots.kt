package com.pronskiy.agenstorm.worktrees

import com.intellij.openapi.util.io.FileUtil

/**
 * Step T1.2, pure. Joins git's list of worktrees with what their admin dirs say. A prunable worktree — its folder is
 * gone, only the admin dir is left — is dropped: there is nothing to switch to.
 */
object WorktreeSnapshots {

    /** What the registry takes from each `GitWorkingTree`. */
    data class GitTree(val path: String, val branch: String?, val isMain: Boolean, val isLocked: Boolean, val isPrunable: Boolean)

    fun build(commonDir: String?, trees: List<GitTree>, admin: Map<String, WorktreeAdminDirs.Admin>): WorktreeSnapshot =
        WorktreeSnapshot(
            commonDir = commonDir?.let(FileUtil::toSystemIndependentName),
            worktrees = trees.filterNot { it.isPrunable }.map { tree ->
                val path = FileUtil.toSystemIndependentName(tree.path)
                val data = if (tree.isMain) null else admin[path]
                Worktree(
                    path = path,
                    branch = tree.branch,
                    isMain = tree.isMain,
                    isLocked = tree.isLocked,
                    lockReason = data?.lockReason?.takeIf { tree.isLocked },
                    createdAt = data?.createdAt ?: 0L,
                    adminId = data?.id,
                )
            },
        )
}
