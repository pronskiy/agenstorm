package com.pronskiy.agenstorm.projectview

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreePath

/**
 * Step O1.2, second attempt. Makes the tree open a gap for the row being named, instead of the field covering
 * whatever was there.
 *
 * A row cannot be *added* — the project view's model is structure-driven and ignores anything put into it
 * directly (decision 52) — but a row can be made taller, and that the tree does honour: `JTree.setCellRenderer`
 * makes `BasicTreeUI` recompute every row's size, so doubling the anchor's height pushes everything below it
 * down by exactly one row and leaves an empty half for the field to sit in.
 *
 * All of it is plain Swing on a component the tree already exposes: `getCellRenderer` out, `setCellRenderer`
 * back in when the field closes. Only the one anchor row is touched; every other row is rendered by the
 * project view's own renderer, untouched, so file colours, icons and selection are exactly as they were.
 */
internal class SpacerRenderer(
    private val delegate: TreeCellRenderer,
    private val anchor: TreePath,
    private val extraHeight: Int,
) : TreeCellRenderer {

    /** One reused panel: the renderer is called for every visible row on every repaint. */
    private val panel = JPanel(BorderLayout()).apply { isOpaque = false }

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        val rendered = delegate.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
        if (tree.getPathForRow(row) != anchor) return rendered

        panel.removeAll()
        panel.add(rendered, BorderLayout.NORTH)
        val natural = rendered.preferredSize
        panel.preferredSize = Dimension(natural.width, natural.height + extraHeight)
        return panel
    }
}
