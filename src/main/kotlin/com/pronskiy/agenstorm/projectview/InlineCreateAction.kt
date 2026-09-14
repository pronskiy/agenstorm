package com.pronskiy.agenstorm.projectview

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.pronskiy.agenstorm.core.AgenstormSettings
import java.awt.Component
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.TreePath

/**
 * Step O1.3. The action in the `NewFile` and `NewDir` slots: instead of a dialog, an editable row on the tree.
 *
 * Every way this cannot work — the feature switched off, the Project tool window never opened, a pane whose
 * nodes are not project-view nodes, a selection that is not a file or a folder, a row the async model has not
 * laid out — hands the event to the action that was displaced. So nothing the IDE can do today stops working;
 * at worst the dialog opens.
 */
class InlineCreateAction(
    private val kind: InlineNameKind,
    private val delegate: AnAction?,
) : AnAction() {

    init {
        // The stock NewFile carries two <override-text> entries; without this the ⌘N popup shows a blank label.
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
        val target = resolveTarget(tree, e.getData(PlatformDataKeys.CONTEXT_COMPONENT)) ?: return fallBack(e)

        // On the EDT, where an action runs, read access is already held, so no read action is needed — and
        // both of these are lookups, which is the limit of what may happen on this thread at all.
        val siblings = target.directory.children.mapTo(HashSet()) { child -> child.name }
        val opened = InlineNameEditor.open(
            tree = tree,
            anchor = target.anchor,
            placement = target.placement,
            kind = kind,
            initialText = "",
            isDirectory = kind == InlineNameKind.NEW_DIRECTORY,
            siblingNames = siblings,
        ) { typed -> commit(project, target.directory, typed) }

        if (opened == null) fallBack(e)
    }

    private fun commit(project: Project, directory: VirtualFile, typed: String) {
        val created = InlineCreate.create(project, directory, typed, kind == InlineNameKind.NEW_DIRECTORY) ?: return
        val file = created.virtualFile ?: return
        // The async model may not have the node yet; selecting after it has settled is the difference between
        // landing on the new row and landing on nothing.
        ApplicationManager.getApplication().invokeLater({
            ProjectTreeAccess.reveal(project, created, file)
            if (created is PsiFile) FileEditorManager.getInstance(project).openFile(file, true)
        }, project.disposed)
    }

    /**
     * `AnAction.actionPerformed` is `@ApiStatus.OverrideOnly`; `ActionUtil.performAction` is the sanctioned way
     * to run someone else's action, and the verifier says so.
     */
    private fun fallBack(e: AnActionEvent) {
        delegate?.let { ActionUtil.performAction(it, e) }
    }

    /** Where the field goes and which folder the result lands in. */
    private data class Target(val anchor: TreePath, val placement: Placement, val directory: VirtualFile)

    /**
     * Decision 53: directly below the row that was clicked, and the folder's last child when the action came
     * from somewhere other than the tree — a menu, or Search Everywhere, where there was no click to be below.
     */
    private fun resolveTarget(tree: JTree, context: Component?): Target? {
        val selected = ProjectTreeAccess.selectedPath(tree) ?: return null
        val file = ProjectTreeAccess.virtualFileOf(selected) ?: return null
        val fromTree = context === tree || (context != null && SwingUtilities.isDescendingFrom(context, tree))

        if (file.isDirectory) {
            // The row below a collapsed folder is its next sibling, which is the wrong place for its child.
            tree.expandPath(selected)
            if (!fromTree) {
                ProjectTreeAccess.lastVisibleDescendant(tree, selected)
                    ?.let { return Target(it, Placement.AS_SIBLING, file) }
            }
            return Target(selected, Placement.AS_CHILD, file)
        }

        val parent = file.parent ?: return null
        if (!fromTree) {
            val parentPath = selected.parentPath ?: return Target(selected, Placement.AS_SIBLING, parent)
            ProjectTreeAccess.lastVisibleDescendant(tree, parentPath)
                ?.let { return Target(it, Placement.AS_SIBLING, parent) }
        }
        return Target(selected, Placement.AS_SIBLING, parent)
    }
}
