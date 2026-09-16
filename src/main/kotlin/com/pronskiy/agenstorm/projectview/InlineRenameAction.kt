package com.pronskiy.agenstorm.projectview

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFileSystemItem
import com.pronskiy.agenstorm.core.AgenstormSettings
import javax.swing.SwingUtilities

/**
 * Step O1.4. The action in the `RenameElement` slot: in the project tree, the row becomes editable; everywhere
 * else — the editor, a tool window, a structure view — the event goes straight to the action it displaced.
 *
 * **Taken with `ActionSlot` rather than registered on the `renameHandler` extension point**, which is what the
 * epic first planned; decision 55. `RenameHandlerRegistry` shows a *chooser dialog* the moment two handlers
 * claim the same element, and in PhpStorm a directory is already claimed by `PlainDirectoryRenameHandler` and a
 * `.md` file by Markdown's own handler — so joining that list would have turned one dialog into two.
 */
class InlineRenameAction(private val delegate: AnAction?) : AnAction() {

    init {
        delegate?.let { templatePresentation.copyFrom(it.templatePresentation, null, true) }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = delegate?.actionUpdateThread ?: ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        if (delegate == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        delegate.update(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        if (project == null || !AgenstormSettings.getInstance().state.projectTreeInlineNamingEnabled) {
            return fallBack(e)
        }
        val tree = ProjectTreeAccess.tree(project) ?: return fallBack(e)

        val context = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        val fromTree = context === tree || (context != null && SwingUtilities.isDescendingFrom(context, tree))
        if (!fromTree) return fallBack(e)

        val anchor = ProjectTreeAccess.selectedPath(tree) ?: return fallBack(e)
        val element = e.getData(CommonDataKeys.PSI_ELEMENT) as? PsiFileSystemItem ?: return fallBack(e)
        // On the EDT, where an action runs, read access is already held; this and the sibling names below are
        // lookups, which is the limit of what may happen on this thread at all.
        if (!InlineRename.canRenameInline(project, element)) return fallBack(e)

        val file = element.virtualFile ?: return fallBack(e)
        val currentName = file.name
        // The element's own name is not a collision with itself.
        val siblings = file.parent?.children.orEmpty()
            .mapNotNullTo(HashSet()) { child -> child.name.takeIf { it != currentName } }

        val opened = InlineNameEditor.open(
            tree = tree,
            anchor = anchor,
            kind = InlineNameKind.RENAME,
            initialText = currentName,
            isDirectory = element is PsiDirectory,
            siblingNames = siblings,
        ) { typed ->
            if (typed != currentName) InlineRename.rename(project, element, typed)
        }

        if (opened == null) fallBack(e)
    }

    /**
     * `AnAction.actionPerformed` is `@ApiStatus.OverrideOnly`; `ActionUtil.performAction` is the sanctioned way
     * to run someone else's action, and the verifier says so.
     */
    private fun fallBack(e: AnActionEvent) {
        delegate?.let { ActionUtil.performAction(it, e) }
    }
}
