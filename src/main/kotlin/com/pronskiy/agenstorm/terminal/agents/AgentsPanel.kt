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
import com.intellij.ui.RowIcon
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
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
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Steps X1.2, X1.4, X2.5, X2.6, X3.2 and X5.2. One frame's view of the Agents sidebar: a group per open project, in the
 * model's order, with this frame's own project in bold — the one thing that differs between windows (decision 102) —
 * and under each the Claude Code sessions running there, then its newest past ones in grey. A click on a running
 * session brings up where it runs, on a past one resumes it in a new tab (decision 103). The
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

    /** The model's groups as last rendered; the rows shown are these, less the folded projects' sessions. */
    private var groups: List<ProjectGroup> = emptyList()

    /** Set while this panel changes its own tree, so the change is not taken for the user's. */
    private var restoring = false

    /**
     * Every row is a child of the hidden root (X5.2): a project's sessions are not its tree children but the rows after
     * it, so each status mark sits right under the project's folder icon, and folding is the panel's own.
     */
    val tree: Tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = false
        cellRenderer = Renderer()
        ClientProperty.put(this, AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
    }

    val scrollPane: JScrollPane = ScrollPaneFactory.createScrollPane(tree, true)

    /** Clicks go here, with the session's id; the IDE carries the plan out, tests record it. */
    internal var onClick: (ClickPlan, String) -> Unit = { plan, sessionId ->
        AgentSessionsModel.getInstance().seen(sessionId)
        AgentSessionOpener.open(plan)
    }

    init {
        add(scrollPane, BorderLayout.CENTER)
        ToolTipManager.sharedInstance().registerComponent(tree)
        tree.addTreeSelectionListener { recordTree { it.apply { selected = tree.selectionPath?.let(::idOf).orEmpty() } } }
        scrollPane.verticalScrollBar.model.addChangeListener { recordTree { it.apply { scroll = scrollPane.verticalScrollBar.value } } }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1) tree.getPathForLocation(e.x, e.y)?.let(::click)
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val path = tree.selectionPath ?: return
                val group = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ProjectGroup
                when {
                    e.keyCode == KeyEvent.VK_ENTER -> click(path)
                    group != null && e.keyCode == KeyEvent.VK_LEFT -> fold(group, folded = true)
                    group != null && e.keyCode == KeyEvent.VK_RIGHT -> fold(group, folded = false)
                    else -> return
                }
                e.consume()
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

    /** EDT. */
    internal fun render(groups: List<ProjectGroup>) {
        this.groups = groups
        show(state.layout.value)
    }

    /** Makes this tree show [layout]'s folds, selection and scroll. EDT. */
    internal fun restore(layout: AgentsSidebarState.Layout) = show(layout)

    /** Rows that only changed in place are updated where they are; a new set of rows is rebuilt. */
    private fun show(layout: AgentsSidebarState.Layout) = restoring(true) {
        val items = groups.flatMap { group -> listOf<Any>(group) + if (group.basePath in layout.collapsed) emptyList() else group.sessions + group.history }
        val nodes = rowNodes()
        if (items.map(::keyOf) == nodes.map { keyOf(it.userObject) }) {
            nodes.zip(items).forEach { (node, item) ->
                if (node.userObject != item) {
                    node.userObject = item
                    treeModel.nodeChanged(node)
                }
            }
        } else {
            root.removeAllChildren()
            items.forEach { root.add(DefaultMutableTreeNode(it, false)) }
            treeModel.reload()
        }
        val selected = rowNodes().firstOrNull { keyOf(it.userObject) == layout.selected }?.let { TreePath(it.path) }
        if (selected == null) tree.clearSelection() else if (tree.selectionPath != selected) tree.selectionPath = selected
        if (scrollPane.verticalScrollBar.value != layout.scroll) scrollPane.verticalScrollBar.value = layout.scroll
    }

    /** Folds or unfolds [group] in every window: a click on a project is the user's, whichever window had the focus. */
    internal fun fold(group: ProjectGroup, folded: Boolean) {
        state.update { layout ->
            layout.apply { if (folded) { if (group.basePath !in collapsed) collapsed.add(group.basePath) } else collapsed.remove(group.basePath) }
        }
        show(state.layout.value)
    }

    /** The groups as shown, top to bottom. */
    internal fun shownGroups(): List<ProjectGroup> = rowNodes().mapNotNull { it.userObject as? ProjectGroup }

    /** Every row as shown, top to bottom: projects, then each one's sessions unless it is folded. */
    internal fun shownRows(): List<Any> = rowNodes().map { it.userObject }

    internal fun isOwn(group: ProjectGroup): Boolean = group.basePath == project.basePath

    internal fun click(path: TreePath) {
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val group = groupAbove(node) ?: return
        when (val item = node.userObject) {
            is ProjectGroup -> fold(item, folded = item.basePath !in state.layout.value.collapsed)
            is SessionRow -> onClick(ClickPlan.of(item, group.basePath), item.session.sessionId)
            is PastSession -> onClick(ClickPlan.of(item, group.basePath), item.sessionId)
        }
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[SESSION_ID] = when (val item = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
            is SessionRow -> item.session.sessionId
            is PastSession -> item.sessionId
            else -> null
        }
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

    private fun rowNodes(): List<DefaultMutableTreeNode> = (0 until root.childCount).map { root.getChildAt(it) as DefaultMutableTreeNode }

    /** The project a row belongs to: itself, or the nearest project row above it. */
    private fun groupAbove(node: DefaultMutableTreeNode): ProjectGroup? {
        val rows = rowNodes()
        val index = rows.indexOf(node)
        if (index < 0) return null
        return (index downTo 0).firstNotNullOfOrNull { rows[it].userObject as? ProjectGroup }
    }

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is ProjectGroup -> {
                    icon = slots(if (item.basePath in state.layout.value.collapsed) AllIcons.General.ChevronRight else AllIcons.General.ChevronDown, AllIcons.Nodes.Folder)
                    append(item.name, if (isOwn(item)) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
                is SessionRow -> {
                    icon = slots(EmptyIcon.ICON_16, iconOf(item))
                    append(SessionRowText.title(item.session), if (item.place == SessionPlace.Elsewhere) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    SessionRowText.ago(item.session.updatedAt, now())?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                    SessionRowText.hint(item)?.let { append("  $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
                    toolTipText = SessionRowText.tooltip(item)
                }
                is PastSession -> {
                    icon = slots(EmptyIcon.ICON_16, AllIcons.Vcs.History)
                    append(item.title, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    SessionRowText.ago(item.lastActivity, now())?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                    toolTipText = AgenstormBundle.message("agents.click.past")
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

        /** Two icons side by side: a project's fold chevron and folder, or a blank and a session's mark under the folder. */
        private fun slots(first: Icon, second: Icon): Icon = RowIcon(first, second)

        /** A row's id in [AgentsSidebarState.Layout.selected]: `group:<base path>`, `session:<id>` or `past:<id>`. */
        fun idOf(path: TreePath): String? = keyOf((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject)

        private fun keyOf(item: Any?): String? = when (item) {
            is ProjectGroup -> "group:${item.basePath}"
            is SessionRow -> "session:${item.session.sessionId}"
            is PastSession -> "past:${item.sessionId}"
            else -> null
        }
    }
}
