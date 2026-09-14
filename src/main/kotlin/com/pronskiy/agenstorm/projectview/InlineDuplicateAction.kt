package com.pronskiy.agenstorm.projectview

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFileSystemItem
import com.pronskiy.agenstorm.core.AgenstormSettings
import javax.swing.SwingUtilities

/**
 * Step O2.3. Copies the selected file or folder next to itself, naming the copy in the tree.
 *
 * **Its own action, not the `CopyElement` slot** — decision 57. F5 offers a target directory as well as a
 * name, and a one-line field cannot; taking it would have removed the folder browser to add an inline name.
 * This adds a way to duplicate without taking anything away, so `CopyElement` still opens its dialog.
 *
 * No default shortcut: ⌘D is `EditorDuplicate` everywhere, and claiming it globally to serve one tree is the
 * kind of trade Epic J already paid for once.
 */
class InlineDuplicateAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val on = AgenstormSettings.getInstance().state.projectTreeInlineNamingEnabled
        val element = e.getData(CommonDataKeys.PSI_ELEMENT)
        e.presentation.isEnabledAndVisible = on && e.project != null && element is PsiFileSystemItem &&
            element.parent is PsiDirectory
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (!AgenstormSettings.getInstance().state.projectTreeInlineNamingEnabled) return
        val source = e.getData(CommonDataKeys.PSI_ELEMENT) as? PsiFileSystemItem ?: return
        val parent = source.parent as? PsiDirectory ?: return

        val tree = ProjectTreeAccess.tree(project) ?: return
        val context = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        val fromTree = context === tree || (context != null && SwingUtilities.isDescendingFrom(context, tree))
        if (!fromTree) return
        val anchor = ProjectTreeAccess.selectedPath(tree) ?: return

        val isDirectory = source is PsiDirectory
        val siblings = parent.virtualFile.children.mapTo(HashSet()) { it.name }
        val suggested = CopyNameSuggester.suggest(source.name, siblings, isDirectory)

        InlineNameEditor.open(
            tree = tree,
            anchor = anchor,
            placement = Placement.AS_SIBLING,
            kind = InlineNameKind.DUPLICATE,
            initialText = suggested,
            isDirectory = isDirectory,
            siblingNames = siblings,
        ) { typed ->
            val created = InlineDuplicate.duplicate(project, source, typed)
            val file = created?.virtualFile ?: parent.virtualFile.findChild(typed)
            if (file != null) {
                ApplicationManager.getApplication().invokeLater({
                    ProjectTreeAccess.reveal(project, created, file)
                }, project.disposed)
            }
        }
    }
}
