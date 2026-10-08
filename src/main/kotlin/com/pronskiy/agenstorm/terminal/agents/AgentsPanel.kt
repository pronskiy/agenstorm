package com.pronskiy.agenstorm.terminal.agents

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Steps X1.2 and X1.4. One frame's view of the Agents sidebar: a group per open project, in the model's order, with this
 * frame's own project in bold — the one thing that differs between windows (decision 102). The selected row, the folded
 * groups and the scroll position are the shared [AgentsSidebarState]'s: changed here only from the window in front
 * ([isFront]), and followed here from every other window.
 */
class AgentsPanel(
    private val project: Project,
    parent: Disposable,
    private val isFront: () -> Boolean = { AgentsSidebarSync.isFront(project) },
) : JPanel(BorderLayout()) {

    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    private val state = AgentsSidebarState.getInstance()

    /** Set while this panel changes its own tree, so the change is not taken for the user's. */
    private var restoring = false

    val tree: Tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
    }

    val scrollPane: JScrollPane = ScrollPaneFactory.createScrollPane(tree, true)

    init {
        add(scrollPane, BorderLayout.CENTER)
        tree.addTreeSelectionListener { recordTree { it.apply { selected = tree.selectionPath?.let(::idOf).orEmpty() } } }
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = recordTree { it.apply { groupOf(event.path)?.let { g -> collapsed.remove(g.basePath) } } }
            override fun treeCollapsed(event: TreeExpansionEvent) = recordTree { it.apply { groupOf(event.path)?.let { g -> if (g.basePath !in collapsed) collapsed.add(g.basePath) } } }
        })
        scrollPane.verticalScrollBar.model.addChangeListener { recordTree { it.apply { scroll = scrollPane.verticalScrollBar.value } } }
        val model = AgentSessionsModel.getInstance()
        model.collect(parent, ::render)
        model.follow(state.layout, parent, ::restore)
    }

    /** EDT. */
    internal fun render(groups: List<ProjectGroup>) {
        restoring(true) {
            root.removeAllChildren()
            groups.forEach { root.add(DefaultMutableTreeNode(it)) }
            treeModel.reload()
        }
        restore(state.layout.value)
    }

    /** Makes this tree show [layout]'s selection, folds and scroll. EDT. */
    internal fun restore(layout: AgentsSidebarState.Layout) = restoring(true) {
        for (node in groupNodes()) {
            val path = TreePath(node.path)
            val group = node.userObject as ProjectGroup
            if (group.basePath in layout.collapsed) tree.collapsePath(path) else tree.expandPath(path)
        }
        val selected = rows().firstOrNull { idOf(it) == layout.selected }
        if (selected == null) tree.clearSelection() else if (tree.selectionPath != selected) tree.selectionPath = selected
        if (scrollPane.verticalScrollBar.value != layout.scroll) scrollPane.verticalScrollBar.value = layout.scroll
    }

    /** The groups as shown, top to bottom. */
    internal fun shownGroups(): List<ProjectGroup> = groupNodes().map { it.userObject as ProjectGroup }

    internal fun isOwn(group: ProjectGroup): Boolean = group.basePath == project.basePath

    private fun recordTree(change: (AgentsSidebarState.Layout) -> AgentsSidebarState.Layout) {
        if (restoring || !isFront()) return
        state.update(change)
    }

    private inline fun restoring(value: Boolean, block: () -> Unit) {
        val before = restoring
        restoring = value
        try {
            block()
        } finally {
            restoring = before
        }
    }

    private fun groupNodes(): List<DefaultMutableTreeNode> = (0 until root.childCount).map { root.getChildAt(it) as DefaultMutableTreeNode }

    private fun rows(): List<TreePath> = TreeUtil.treePathTraverser(tree).preOrderDfsTraversal().filter { it.pathCount > 1 }.toList()

    private fun groupOf(path: TreePath): ProjectGroup? = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ProjectGroup

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val group = (value as? DefaultMutableTreeNode)?.userObject as? ProjectGroup ?: return
            icon = AllIcons.Nodes.Folder
            append(group.name, if (isOwn(group)) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    companion object {
        /** A row's id in [AgentsSidebarState.Layout.selected]: `group:<base path>` for a project; sessions come in X2. */
        fun idOf(path: TreePath): String? = when (val item = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
            is ProjectGroup -> "group:${item.basePath}"
            else -> null
        }
    }
}
