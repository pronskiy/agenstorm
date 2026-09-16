package com.pronskiy.agenstorm.projectview

import com.intellij.refactoring.RefactoringSettings
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.refactoring.rename.naming.AutomaticRenamer

/**
 * Decision 62. The automatic-renaming question — "rename class `Client` too?" when `Client.php` becomes
 * `HttpClient.php` — answered the way the dialog's OK button would answer it, without the dialog.
 *
 * `AutomaticRenamingDialog` pre-checks every suggestion when its renamer is selected by default and none when it is
 * not, and OK then keeps the checked ones and drops the rest. PhpStorm's four renamers — the class in the file,
 * inheritors, field accessors, parameters — all say they are selected by default, so in practice the class follows
 * the file, exactly as it does when you press OK. Which renamers run at all is still the user's own
 * refactoring settings; nothing here widens that.
 */
object AutomaticRenames {

    /** What OK does to [renamer] when nobody has touched a checkbox. */
    fun acceptAsOk(renamer: AutomaticRenamer) {
        val selected = renamer.isSelectedByDefault
        // A suggestion without a name is not a row in the dialog, so OK never sees it.
        val suggested = renamer.renames.filterValues { it != null }
        for ((element, newName) in suggested) {
            if (selected) renamer.setRename(element, newName) else renamer.doNotRename(element)
        }
    }
}

/**
 * A rename processor whose automatic-renaming step never shows a dialog. `showAutomaticRenamingDialog` is the
 * protected hook the platform itself uses for this in test mode; everything else is the stock processor.
 */
internal class AcceptingRenameProcessor(
    project: Project,
    element: PsiElement,
    newName: String,
    searchInComments: Boolean,
    searchTextOccurrences: Boolean,
) : RenameProcessor(project, element, newName, searchInComments, searchTextOccurrences) {

    override fun showAutomaticRenamingDialog(automaticVariableRenamer: AutomaticRenamer): Boolean {
        // Mirrors the stock processor: with automatic renaming switched off, the renamer does not run at all.
        if (!RefactoringSettings.getInstance().RENAME_SHOW_AUTOMATIC_RENAMING_DIALOG) return false
        AutomaticRenames.acceptAsOk(automaticVariableRenamer)
        return true
    }
}
