package com.pronskiy.agenstorm.terminal.agents

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Step X1.2. One frame's view of the Agents sidebar: a group per open project, in the model's order, with this frame's
 * own project in bold — the one thing that differs between windows (decision 102).
 */
class AgentsPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {

    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)

    val tree: Tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
    }

    init {
        add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        AgentSessionsModel.getInstance().collect(parent, ::render)
    }

    /** EDT. */
    internal fun render(groups: List<ProjectGroup>) {
        root.removeAllChildren()
        groups.forEach { root.add(DefaultMutableTreeNode(it)) }
        treeModel.reload()
    }

    /** The groups as shown, top to bottom. */
    internal fun shownGroups(): List<ProjectGroup> =
        (0 until root.childCount).map { (root.getChildAt(it) as DefaultMutableTreeNode).userObject as ProjectGroup }

    internal fun isOwn(group: ProjectGroup): Boolean = group.basePath == project.basePath

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val group = (value as? DefaultMutableTreeNode)?.userObject as? ProjectGroup ?: return
            icon = AllIcons.Nodes.Folder
            append(group.name, if (isOwn(group)) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }
}
