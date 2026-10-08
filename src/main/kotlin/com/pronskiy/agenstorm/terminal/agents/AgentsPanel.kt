package com.pronskiy.agenstorm.terminal.agents

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ClientProperty
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import com.pronskiy.agenstorm.core.AgenstormBundle
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.ToolTipManager
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Steps X1.2, X1.4, X2.5 and X2.6. One frame's view of the Agents sidebar: a group per open project, in the model's
 * order, with this frame's own project in bold — the one thing that differs between windows (decision 102) — and under
 * each the Claude Code sessions running there. A click on a session brings up where it runs (decision 103). The
 * selected row, the folded groups and the scroll position are the shared [AgentsSidebarState]'s: changed here only from
 * the window in front ([isFront]), and followed here from every other window.
 */
class AgentsPanel(
    private val project: Project,
    parent: Disposable,
    private val isFront: () -> Boolean = { AgentsSidebarSync.isFront(project) },
    private val now: () -> Long = System::currentTimeMillis,
) : JPanel(BorderLayout()), UiDataProvider {

    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    private val state = AgentsSidebarState.getInstance()

    /** Set while this panel changes its own tree, so the change is not taken for the user's. */
    private var restoring = false

    val tree: Tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
        ClientProperty.put(this, AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
    }

    val scrollPane: JScrollPane = ScrollPaneFactory.createScrollPane(tree, true)

    /** Clicks go here; the IDE opens the session, tests record it. */
    internal var onClick: (SessionRow, ProjectGroup) -> Unit = { row, group ->
        AgentSessionsModel.getInstance().seen(row.session.sessionId)
        AgentSessionOpener.open(ClickPlan.of(row, group.basePath))
    }

    init {
        add(scrollPane, BorderLayout.CENTER)
        ToolTipManager.sharedInstance().registerComponent(tree)
        tree.addTreeSelectionListener { recordTree { it.apply { selected = tree.selectionPath?.let(::idOf).orEmpty() } } }
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = recordTree { it.apply { groupOf(event.path)?.let { g -> collapsed.remove(g.basePath) } } }
            override fun treeCollapsed(event: TreeExpansionEvent) = recordTree { it.apply { groupOf(event.path)?.let { g -> if (g.basePath !in collapsed) collapsed.add(g.basePath) } } }
        })
        scrollPane.verticalScrollBar.model.addChangeListener { recordTree { it.apply { scroll = scrollPane.verticalScrollBar.value } } }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1) tree.getPathForLocation(e.x, e.y)?.let(::click)
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) tree.selectionPath?.let(::click)
            }
        })
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(CopySessionIdAction()), "AgentsSidebarPopup")
        // The ages ("5m") move on with no new read.
        val ages = Timer(AGE_REPAINT_MS) { tree.repaint() }.apply { start() }
        Disposer.register(parent) { ages.stop() }
        val model = AgentSessionsModel.getInstance()
        model.collect(parent, ::render)
        model.follow(state.layout, parent, ::restore)
    }

    /** EDT. Rows that only changed in place are updated where they are; a new shape is rebuilt. */
    internal fun render(groups: List<ProjectGroup>) {
        if (shapeOf(groups) == shapeOf(shownGroups())) {
            groupNodes().zip(groups).forEach { (node, group) ->
                node.userObject = group
                treeModel.nodeChanged(node)
                (0 until node.childCount).map { node.getChildAt(it) as DefaultMutableTreeNode }.zip(group.sessions).forEach { (child, row) ->
                    child.userObject = row
                    treeModel.nodeChanged(child)
                }
            }
            return
        }
        restoring(true) {
            root.removeAllChildren()
            for (group in groups) {
                val node = DefaultMutableTreeNode(group)
                group.sessions.forEach { node.add(DefaultMutableTreeNode(it, false)) }
                root.add(node)
            }
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

    internal fun click(path: TreePath) {
        val row = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? SessionRow ?: return
        val group = (path.parentPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ProjectGroup ?: return
        onClick(row, group)
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[SESSION_ID] = ((tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? SessionRow)?.session?.sessionId
    }

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
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is ProjectGroup -> {
                    icon = AllIcons.Nodes.Folder
                    append(item.name, if (isOwn(item)) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
                is SessionRow -> {
                    icon = iconOf(item)
                    append(SessionRowText.title(item.session), if (item.place == SessionPlace.Elsewhere) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    SessionRowText.ago(item.session.updatedAt, now())?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                    SessionRowText.hint(item)?.let { append("  $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
                    toolTipText = SessionRowText.tooltip(item)
                }
            }
        }
    }

    /** Copies the selected session's id, for `claude --resume <id>` by hand. */
    private class CopySessionIdAction : DumbAwareAction(AgenstormBundle.messagePointer("agents.copySessionId")) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = e.getData(SESSION_ID) != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            e.getData(SESSION_ID)?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
        }
    }

    /** A small round status mark in a 16 px icon. */
    private class Dot(private val color: Color) : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                val d = JBUI.scale(7)
                g2.fillOval(x + (iconWidth - d) / 2, y + (iconHeight - d) / 2, d, d)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth() = JBUI.scale(16)
        override fun getIconHeight() = JBUI.scale(16)
    }

    companion object {
        /** The selected session's id, for the popup's actions. */
        val SESSION_ID: DataKey<String> = DataKey.create("Agenstorm.Agents.SessionId")

        private const val AGE_REPAINT_MS = 30_000

        private val IDLE = Dot(JBColor(0xA8ADBD, 0x6F737A))
        private val WAITING = Dot(JBColor(0xE08855, 0xE08855))
        private val FINISHED = Dot(JBColor(0x3574F0, 0x548AF7))

        /** A row's status mark: spinning while Claude works, blue once it finished while away, orange while it waits. */
        internal fun iconOf(row: SessionRow): Icon = when {
            row.session.status == "busy" -> AnimatedIcon.Default.INSTANCE
            row.finished -> FINISHED
            row.session.status == "waiting" -> WAITING
            else -> IDLE
        }

        /** A row's id in [AgentsSidebarState.Layout.selected]: `group:<base path>` for a project, `session:<id>` for a session. */
        fun idOf(path: TreePath): String? = when (val item = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
            is ProjectGroup -> "group:${item.basePath}"
            is SessionRow -> "session:${item.session.sessionId}"
            else -> null
        }

        /** What decides whether rows can be updated in place: the projects and their sessions, in order. */
        private fun shapeOf(groups: List<ProjectGroup>) = groups.map { group -> group.basePath to group.sessions.map { it.session.sessionId } }
    }
}
