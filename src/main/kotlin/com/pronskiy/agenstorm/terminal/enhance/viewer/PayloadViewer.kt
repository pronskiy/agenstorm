package com.pronskiy.agenstorm.terminal.enhance.viewer

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.pronskiy.agenstorm.core.AgenstormBundle
import java.awt.Dimension
import java.awt.Point
import java.awt.datatransfer.StringSelection
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Step I2.2. The popup over a `tree` or `json` block: the parsed payload as an expandable tree — keys, values
 * with their type as the output wrote them — with the first two levels open, speed search, and a context menu
 * that copies the node, its subtree or the block's raw text.
 */
object PayloadViewer {

    private const val DIMENSION_KEY = "agenstorm.terminal.enhancer.viewer"

    fun show(project: Project, editor: Editor, at: Point, title: String, root: PayloadNode, raw: String) {
        val treeRoot = DefaultMutableTreeNode(root).also { build(it, root) }
        val tree = Tree(DefaultTreeModel(treeRoot)).apply {
            isRootVisible = true
            showsRootHandles = true
            cellRenderer = Renderer()
        }
        for (row in tree.rowCount - 1 downTo 0) tree.expandRow(row)
        expandTo(tree, treeRoot, depth = 2)
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(CopyAction("copyNode") { it.label }, CopyAction("copySubtree") { it.subtreeText() }, CopyRawAction(raw)), "AgenstormEnhancerViewer")
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JBScrollPane(tree), tree)
            .setTitle(title)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setDimensionServiceKey(project, DIMENSION_KEY, false)
            .setMinSize(Dimension(JBUI.scale(320), JBUI.scale(160)))
            .createPopup()
        popup.show(RelativePoint(editor.contentComponent, at))
    }

    private fun build(node: DefaultMutableTreeNode, payload: PayloadNode) {
        for (child in payload.children) node.add(DefaultMutableTreeNode(child).also { build(it, child) })
    }

    /** Collapses everything, then opens the root and its children: enough to see the shape, not a wall. */
    private fun expandTo(tree: JTree, root: DefaultMutableTreeNode, depth: Int) {
        for (row in tree.rowCount - 1 downTo 1) tree.collapseRow(row)
        fun open(node: DefaultMutableTreeNode, level: Int) {
            if (level >= depth) return
            tree.expandPath(javax.swing.tree.TreePath(node.path))
            for (i in 0 until node.childCount) open(node.getChildAt(i) as DefaultMutableTreeNode, level + 1)
        }
        open(root, 0)
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val payload = (value as? DefaultMutableTreeNode)?.userObject as? PayloadNode ?: return
            if (payload.key != null) {
                append(payload.key, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append(" => ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            append(payload.text, if (payload.isContainer) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    /** Copies from the selected node; the selection is read in [actionPerformed], so `update` touches no Swing. */
    private class CopyAction(key: String, private val text: (PayloadNode) -> String) :
        DumbAwareAction(AgenstormBundle.message("terminal.enhancer.viewer.$key")) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            val tree = e.getData(com.intellij.openapi.actionSystem.PlatformDataKeys.CONTEXT_COMPONENT) as? JTree ?: return
            val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? PayloadNode ?: return
            CopyPasteManager.getInstance().setContents(StringSelection(text(node)))
        }
    }

    private class CopyRawAction(private val raw: String) : DumbAwareAction(AgenstormBundle.message("terminal.enhancer.viewer.copyRaw")) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            CopyPasteManager.getInstance().setContents(StringSelection(raw))
        }
    }
}
