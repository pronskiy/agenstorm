package com.pronskiy.agenstorm.projectview

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.PlainDirectoryRenameHandler
import com.intellij.refactoring.rename.PsiElementRenameHandler

/**
 * Step O1.4. Which elements may be renamed in the row, and the rename itself.
 *
 * **The gate exists because a rename handler can be more than a pass-through.** `PlainDirectoryRenameHandler`
 * and Markdown's `MarkdownFileRenameHandler` both end in `PsiElementRenameHandler.rename` — they are registered
 * only to force the platform's chooser when something else also claims the element — so taking their place
 * changes nothing but the dialog. `PhpDirectoryPackageRenameHandler`, `RenameProjectHandler`,
 * `ProjectFolderRenameHandler` and `RenameModuleHandler` are not pass-throughs: they carry namespace, project
 * and module work that only their own dialog offers. Those elements are handed back, dialog and all.
 *
 * The test avoids naming any of those classes: a directory is inline-renameable when the platform's own public
 * `PlainDirectoryRenameHandler.isPlainDirectory` says no package handler claims it, and when it is neither a
 * content root nor the project's own folder.
 */
object InlineRename {

    fun canRenameInline(project: Project, element: PsiElement): Boolean = when (element) {
        is PsiFile -> PsiElementRenameHandler.canRename(project, null, element)
        is PsiDirectory -> isPlainEnough(project, element) && PsiElementRenameHandler.canRename(project, null, element)
        else -> false
    }

    private fun isPlainEnough(project: Project, directory: PsiDirectory): Boolean {
        if (!PlainDirectoryRenameHandler.isPlainDirectory(directory)) return false
        val file = directory.virtualFile
        if (file.path == project.basePath) return false
        return ProjectRootManager.getInstance(project).contentRoots.none { it == file }
    }

    /**
     * Runs the rename with the name already chosen. With a non-null name `PsiElementRenameHandler.rename`
     * builds the Rename dialog and disposes it without showing it, so the element's own
     * `RenamePsiElementProcessor` still runs — which is what keeps a PHP class file's usages correct — while
     * the dialog nobody asked for never appears. The refactoring makes its own command, so this must not be
     * wrapped in one: it is already a single undo step (decision 53).
     */
    fun rename(project: Project, element: PsiElement, newName: String) {
        PsiElementRenameHandler.rename(element, project, element, null, newName)
    }
}
