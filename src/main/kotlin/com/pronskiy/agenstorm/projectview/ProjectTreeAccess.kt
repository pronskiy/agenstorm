package com.pronskiy.agenstorm.projectview

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.util.ui.tree.TreeUtil
import java.awt.Rectangle
import javax.swing.JTree
import javax.swing.tree.TreePath

/**
 * Step O1.2. The only file in the epic that talks to the project view pane, so the platform surface the feature
 * stands on is one import list long.
 *
 * Everything here is EDT-only and every method answers `null` rather than throwing: the Project tool window may
 * never have been opened (`AbstractProjectViewPane.getTree()` hands back a field that is null until
 * `createComponent`), the current pane may be Scratches or a third-party pane whose nodes are not
 * [ProjectViewNode]s, and a path the async model has not laid out yet has no bounds. Each of those means the
 * inline field cannot open, and the caller falls back to the dialog the feature displaced.
 */
object ProjectTreeAccess {

    /** The tree of whichever pane the Project tool window is showing, or `null` if there is nothing to show. */
    fun tree(project: Project): JTree? =
        ProjectView.getInstance(project).currentProjectViewPane?.tree

    /** The single selected row, or `null` for no selection and for a multi-selection. */
    fun selectedPath(tree: JTree): TreePath? = tree.selectionPaths?.singleOrNull()

    /** The project-view node a row stands for, or `null` for a row that is not one. */
    fun nodeOf(path: TreePath): ProjectViewNode<*>? =
        TreeUtil.getUserObject(ProjectViewNode::class.java, path.lastPathComponent)

    /** The file or folder a row stands for, or `null` for a row that is not a [ProjectViewNode]. */
    fun virtualFileOf(path: TreePath): VirtualFile? = nodeOf(path)?.virtualFile

    /** Bounds of a row, or `null` when the row is not laid out — collapsed, or not materialised yet. */
    fun boundsOf(tree: JTree, path: TreePath): Rectangle? = tree.getPathBounds(path)

    /**
     * Asks the current pane to rebuild one folder's children, which is what makes the placeholder node appear and
     * disappear. `updateFrom(element, forceResort, updateStructure)` is the overload the platform documents as the
     * one for plugins; its sibling that takes an update cause is internal.
     */
    fun refresh(project: Project, directory: PsiDirectory): Boolean {
        val pane = ProjectView.getInstance(project).currentProjectViewPane ?: return false
        pane.updateFrom(directory, false, true)
        return true
    }

    /** The row the tree built for [placeholder], or `null` while the async model has not got to it yet. */
    fun placeholderRow(tree: JTree, placeholder: InlinePlaceholder): TreePath? {
        for (row in 0 until tree.rowCount) {
            val path = tree.getPathForRow(row) ?: continue
            val node = TreeUtil.getUserObject(PlaceholderNode::class.java, path.lastPathComponent) ?: continue
            if (node.value === placeholder) return path
        }
        return null
    }

    /** Puts the tree's selection on what was just created or renamed. Safe to call for a node not there yet. */
    fun reveal(project: Project, element: Any?, file: VirtualFile) {
        ProjectView.getInstance(project).select(element, file, true)
    }
}
