package com.pronskiy.agenstorm.projectview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import javax.swing.JTree
import javax.swing.Timer
import javax.swing.tree.TreePath
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener

/**
 * Decision 61. Opens the field for a *new* element: puts a placeholder node into the target folder, waits for the
 * tree to build its row — which moves every row below it down — then draws the field on that row. The placeholder
 * goes when the field does.
 *
 * The wait is event-driven: the tree's model announces the new row, and a short timer catches the case where it
 * never does (a pane that ignores structure providers, a folder that cannot be refreshed). That case falls back to
 * whatever [onUnavailable] does, which for the displaced platform actions is their own dialog.
 */
internal object InlineRowSession {

    /** Long enough for a busy async model, short enough that a pane which will never show the row is not a hang. */
    private const val WAIT_MS = 2_000

    /**
     * Expanding from inside a model event is re-entrant, so it is queued; the expansion listener picks up the row it
     * reveals. A no-op for a placeholder whose folder is already open.
     */
    private fun JTree.expandLater(path: TreePath?, stillWaiting: () -> Boolean) {
        if (path == null || isExpanded(path)) return
        ApplicationManager.getApplication().invokeLater {
            if (stillWaiting() && !isExpanded(path)) expandPath(path)
        }
    }

    fun start(
        project: Project,
        tree: JTree,
        target: InlineTarget,
        kind: InlineNameKind,
        initialText: String,
        isDirectory: Boolean,
        siblingNames: Set<String>,
        selectionEnd: Int = InlineNamePolicy.selectionEnd(initialText, isDirectory),
        onUnavailable: () -> Unit,
        onCommit: (String) -> Unit,
    ) {
        val directory = PsiManager.getInstance(project).findDirectory(target.directory) ?: return onUnavailable()
        val placeholder = InlinePlaceholder(project, target.directory, target.position, target.anchor, isDirectory)
        InlinePlaceholders.show(placeholder)
        if (!ProjectTreeAccess.refresh(project, directory)) {
            InlinePlaceholders.clear(placeholder)
            return onUnavailable()
        }

        var settled = false
        lateinit var listener: TreeModelListener
        lateinit var expansionListener: TreeExpansionListener
        lateinit var timer: Timer

        fun stopWaiting() {
            settled = true
            timer.stop()
            tree.model?.removeTreeModelListener(listener)
            tree.removeTreeExpansionListener(expansionListener)
        }

        fun expandTheFolder() = tree.expandLater(target.folderPath) { !settled }

        fun removePlaceholder() {
            InlinePlaceholders.clear(placeholder)
            ApplicationManager.getApplication().invokeLater({
                if (directory.isValid) ProjectTreeAccess.refresh(project, directory)
            }, project.disposed)
        }

        fun openOn() {
            if (settled) return
            val row = ProjectTreeAccess.placeholderRow(tree, placeholder)
            if (row == null) {
                expandTheFolder()
                return
            }
            stopWaiting()
            val editor = InlineNameEditor.open(
                tree = tree,
                anchor = row,
                kind = kind,
                initialText = initialText,
                isDirectory = isDirectory,
                siblingNames = siblingNames,
                selectionEnd = selectionEnd,
                relocate = { ProjectTreeAccess.placeholderRow(tree, placeholder) },
                // Cleared before the commit runs, so the refresh that brings in the new file no longer builds the
                // placeholder and the row is simply replaced; a cancel refreshes to take it away.
                onClose = { removePlaceholder() },
                onCommit = onCommit,
            )
            if (editor == null) {
                removePlaceholder()
                onUnavailable()
            }
        }

        listener = object : TreeModelListener {
            override fun treeNodesChanged(e: TreeModelEvent) = openOn()
            override fun treeNodesInserted(e: TreeModelEvent) = openOn()
            override fun treeNodesRemoved(e: TreeModelEvent) = openOn()
            override fun treeStructureChanged(e: TreeModelEvent) = openOn()
        }
        // The row of a placeholder inside a folder that was empty only exists once that folder is expanded, and the
        // folder could not be expanded while it had no children. The model listener sees the child arrive; this sees
        // the expansion that makes its row visible.
        expansionListener = object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = openOn()
            override fun treeCollapsed(event: TreeExpansionEvent) = Unit
        }
        timer = Timer(WAIT_MS) {
            if (settled) return@Timer
            stopWaiting()
            // A second invocation replaces the pending placeholder; this session then has nothing to fall back for.
            if (InlinePlaceholders.pending !== placeholder) return@Timer
            removePlaceholder()
            onUnavailable()
        }.apply { isRepeats = false }

        tree.model?.addTreeModelListener(listener)
        tree.addTreeExpansionListener(expansionListener)
        timer.start()
        openOn()
    }
}
