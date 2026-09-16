package com.pronskiy.agenstorm.projectview

import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.JTree

/** Which folder a new element lands in, and where the row being named sits among that folder's children. */
internal data class InlineTarget(
    val directory: VirtualFile,
    val position: PlaceholderPosition,
    val anchor: ProjectViewNode<*>?,
) {

    companion object {

        /**
         * Decision 53, refined by Roman's answer to decision 61: directly below the row that was clicked — as the
         * first child of a clicked folder, or right under a clicked file — and the folder's last child when the
         * action came from somewhere other than the tree, where there was no click to be below.
         */
        fun resolve(tree: JTree, fromTree: Boolean): InlineTarget? {
            val selected = ProjectTreeAccess.selectedPath(tree) ?: return null
            val node = ProjectTreeAccess.nodeOf(selected) ?: return null
            val file = node.virtualFile ?: return null

            if (file.isDirectory) {
                // The placeholder is the folder's child, so the folder has to be open for its row to exist.
                tree.expandPath(selected)
                return InlineTarget(file, if (fromTree) PlaceholderPosition.FIRST else PlaceholderPosition.LAST, anchor = null)
            }
            val parent = file.parent ?: return null
            return if (fromTree) {
                InlineTarget(parent, PlaceholderPosition.AFTER_ANCHOR, node)
            } else {
                InlineTarget(parent, PlaceholderPosition.LAST, anchor = null)
            }
        }
    }
}
