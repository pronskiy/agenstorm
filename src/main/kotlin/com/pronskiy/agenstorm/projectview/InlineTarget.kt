package com.pronskiy.agenstorm.projectview

import com.intellij.openapi.vfs.VirtualFile
import javax.swing.JTree
import javax.swing.tree.TreePath

/** Where the field goes and which folder the result lands in. */
internal data class InlineTarget(val anchor: TreePath, val placement: Placement, val directory: VirtualFile) {

    companion object {

        /**
         * Decision 53: directly below the row that was clicked, and the folder's last child when the action
         * came from somewhere other than the tree — a menu, or Search Everywhere, where there was no click to
         * be below.
         */
        fun resolve(tree: JTree, fromTree: Boolean): InlineTarget? {
            val selected = ProjectTreeAccess.selectedPath(tree) ?: return null
            val file = ProjectTreeAccess.virtualFileOf(selected) ?: return null

            if (file.isDirectory) {
                // The row below a collapsed folder is its next sibling, the wrong place for its child.
                tree.expandPath(selected)
                if (!fromTree) {
                    ProjectTreeAccess.lastVisibleDescendant(tree, selected)
                        ?.let { return InlineTarget(it, Placement.AS_SIBLING, file) }
                }
                return InlineTarget(selected, Placement.AS_CHILD, file)
            }

            val parent = file.parent ?: return null
            if (!fromTree) {
                val parentPath = selected.parentPath ?: return InlineTarget(selected, Placement.AS_SIBLING, parent)
                ProjectTreeAccess.lastVisibleDescendant(tree, parentPath)
                    ?.let { return InlineTarget(it, Placement.AS_SIBLING, parent) }
            }
            return InlineTarget(selected, Placement.AS_SIBLING, parent)
        }
    }
}
