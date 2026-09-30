package com.pronskiy.agenstorm.worktrees.ui

import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.pronskiy.agenstorm.worktrees.WorktreeExcludes
import com.pronskiy.agenstorm.worktrees.WorktreeRegistry

/**
 * The folders the project excludes because they hold worktrees (T1.7) — `.worktrees`, `.claude/worktrees` and any other
 * nested worktree — are left out of the Project pane as `.idea` is, rather than shown there in the excluded colour; the
 * *Project Files* pane still lists them. Roman, 2026-09-30: "it should not be shown in project view, only in project
 * files view, similar to .idea folder". The platform hides `.idea` inside `ProjectViewDirectoryHelper`, which has no
 * hook, so this is a `TreeStructureProvider` that drops those folders' `PsiDirectoryNode`s; the scope-based panes
 * (*Project Files* among them) build their own node type and are left alone. [hidden] is replaceable for the tests.
 */
class WorktreeFolderFilter(private val hidden: (Project) -> Set<String> = ::hiddenFolders) : TreeStructureProvider, DumbAware {

    override fun modify(parent: AbstractTreeNode<*>, children: Collection<AbstractTreeNode<*>>, settings: ViewSettings?): Collection<AbstractTreeNode<*>> {
        if (children.none { it is PsiDirectoryNode }) return children
        val project = parent.project?.takeUnless { it.isDisposed } ?: return children
        val folders = hidden(project)
        if (folders.isEmpty()) return children
        return children.filterNot { it is PsiDirectoryNode && it.virtualFile?.path in folders }
    }

    companion object {
        fun hiddenFolders(project: Project): Set<String> {
            val base = project.basePath?.let(FileUtil::toSystemIndependentName) ?: return emptySet()
            return WorktreeExcludes.paths(base, WorktreeExcludes.configuredFolder(), WorktreeRegistry.getInstance(project).state.value.worktrees).toSet()
        }
    }
}
