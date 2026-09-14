package com.pronskiy.agenstorm.projectview

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.PlainDirectoryRenameHandler
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory

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
     * Runs the rename with the name already chosen, assembling the processor the way `RenameDialog` would:
     * the element's own `RenamePsiElementProcessor` decides substitution and the search-in-comments and
     * search-in-text flags, and every automatic renamer the user has enabled is added, so a PHP class file's
     * usages are still updated and the refactoring's own follow-up questions still appear (decision 53).
     *
     * **The one thing taken away is the usage preview**, decision 56. This is why the processor is assembled
     * here rather than left to `PsiElementRenameHandler.rename`: `RefactoringDialog` initialises its preview
     * flag to `true` and only the dialog's own checkbox ever clears it, so a dialog that is built and disposed
     * without being shown hands the processor `setPreviewUsages(true)` — the Find tool window opened on every
     * inline rename. Setting it to `false` leaves `RenameProcessor.isPreviewUsages` with only its other
     * trigger, `reportNonRegularUsages`: a rename that reaches into comments or plain text still shows its
     * list first, and a rename that is only code references just happens.
     *
     * The refactoring makes its own command, so this must not be wrapped in one: it is already one undo step.
     */
    fun rename(project: Project, element: PsiElement, newName: String) {
        val elementProcessor = RenamePsiElementProcessor.forElement(element)
        val target = elementProcessor.substituteElementToRename(element, null) ?: return
        val processor = RenameProcessor(
            project,
            target,
            newName,
            elementProcessor.isToSearchInComments(target),
            elementProcessor.isToSearchForTextOccurrences(target),
        )
        for (factory in AutomaticRenamerFactory.EP_NAME.extensionList) {
            if (factory.isApplicable(target) && factory.optionName != null && factory.isEnabled) {
                processor.addRenamerFactory(factory)
            }
        }
        processor.setPreviewUsages(false)
        processor.run()
    }
}
