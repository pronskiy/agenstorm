package com.pronskiy.agenstorm.projectview

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.Rectangle
import javax.swing.JTree
import javax.swing.tree.TreePath

/**
 * Step O1.2. The only file in the epic that names a project-view type, so the platform surface the feature
 * stands on is one import list long.
 *
 * Everything here is EDT-only and every method answers `null` rather than throwing: the Project tool window
 * may never have been opened (`AbstractProjectViewPane.getTree()` hands back a field that is null until
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

    /** The file or folder a row stands for, or `null` for a row that is not a [ProjectViewNode]. */
    fun virtualFileOf(path: TreePath): VirtualFile? =
        TreeUtil.getUserObject(ProjectViewNode::class.java, path.lastPathComponent)?.virtualFile

    /** Bounds of a row, or `null` when the row is not laid out — collapsed, or not materialised yet. */
    fun boundsOf(tree: JTree, path: TreePath): Rectangle? = tree.getPathBounds(path)

    /**
     * How far one tree level is indented, measured off the tree rather than read out of `UIManager`:
     * `DefaultTreeUI` indents through its own `Control.Painter`, so the `BasicTreeUI` defaults do not describe
     * what is painted. Falls back to a scaled 16 for a tree with no visible parent/child pair to measure from.
     */
    fun indentPerLevel(tree: JTree): Int {
        val samples = ArrayList<Int>(4)
        for (row in 1 until tree.rowCount) {
            val path = tree.getPathForRow(row) ?: continue
            val parent = path.parentPath ?: continue
            val childBounds = tree.getPathBounds(path) ?: continue
            val parentBounds = tree.getPathBounds(parent) ?: continue
            samples.add(childBounds.x - parentBounds.x)
            if (samples.size >= 4) break
        }
        return InlineRowGeometry.indentPerLevel(samples, JBUI.scale(16))
    }

    /**
     * The last row still under [parent], used when there was no click to anchor to and the field goes to the
     * folder's end instead (decision 53). `null` when [parent] is collapsed or has no visible children.
     */
    fun lastVisibleDescendant(tree: JTree, parent: TreePath): TreePath? {
        val parentRow = tree.getRowForPath(parent)
        if (parentRow < 0) return null
        var last: TreePath? = null
        for (row in parentRow + 1 until tree.rowCount) {
            val path = tree.getPathForRow(row) ?: break
            if (!parent.isDescendant(path)) break
            last = path
        }
        return last
    }

    /** Puts the tree's selection on what was just created or renamed. Safe to call for a node not there yet. */
    fun reveal(project: Project, element: Any?, file: VirtualFile) {
        ProjectView.getInstance(project).select(element, file, true)
    }
}
