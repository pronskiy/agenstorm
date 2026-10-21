package com.pronskiy.agenstorm.tabs.ui

import com.intellij.ide.ReopenProjectAction
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopupStep
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.popup.ActionPopupStep
import com.intellij.ui.popup.PopupFactoryImpl.ActionItem
import com.intellij.ui.popup.list.SelectablePanel
import com.intellij.ui.speedSearch.ListWithFilter
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

/**
 * The "+" popup: the recent projects in a list of their own, at most [MAX_VISIBLE_PROJECTS] rows tall and scrolling
 * past that, and under it the stock widget's actions (Open…, Get from Version Control…), always in view (Roman,
 * 2026-10-19 — in one long list they were below the fold). Projects sharing a name show their parent folder in
 * front, `SomeFolder/infrastructure` ([RecentProjectRenderer]).
 *
 * The items, their presentation and what choosing one does all come from [step], the platform's own step for the
 * whole group, so a choice does what it did in the single list this replaces. Up and Down move through both lists
 * and wrap around, Enter chooses, typing filters the projects, a click chooses what is under the mouse.
 */
class AddProjectPopup(private val step: ActionPopupStep, branchOf: (String) -> String?) {

    private val items = step.values
    private val projectsRenderer = RecentProjectRenderer(items.map { it.action }.filterIsInstance<ReopenProjectAction>(), branchOf)
    internal val projects = list(items.filter { it.action is ReopenProjectAction }, projectsRenderer)
    /** The name the row of [action] shows, parent folder included when the list shares the name. */
    internal fun nameOf(action: ReopenProjectAction) = projectsRenderer.nameFor(action)
    internal val commands = list(items.filter { it.action !is ReopenProjectAction }, ListCellRenderer { _, item, _, selected, _ ->
        command(item.text, item.getIcon(selected), selected)
    })

    val popup: JBPopup
    private var anchor: Component? = null

    init {
        projects.visibleRowCount = projects.model.size.coerceIn(1, MAX_VISIBLE_PROJECTS)
        // The keyboard stays in the projects list, where typing filters; the actions are reached with the arrows.
        commands.isFocusable = projects.model.size == 0
        val content = JPanel(BorderLayout()).apply { background = JBUI.CurrentTheme.Popup.BACKGROUND }
        if (projects.model.size > 0) {
            val scroll = object : JBScrollPane(projects, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER) {
                // The viewport adds 12 px to the rows it is asked to show, and half a thirteenth row showed in them.
                override fun getPreferredSize(): Dimension = super.getPreferredSize().apply {
                    height = projects.preferredScrollableViewportSize.height + insets.top + insets.bottom
                }
            }.apply {
                border = JBUI.Borders.empty()
                viewportBorder = JBUI.Borders.empty()
            }
            // The speed search matches what the rows show, prefixed names for same-named projects included.
            content.add(ListWithFilter.wrap(projects, scroll, { item: ActionItem ->
                (item.action as? ReopenProjectAction)?.let(projectsRenderer::nameFor) ?: item.text
            }), BorderLayout.CENTER)
        }
        if (commands.model.size > 0) {
            content.add(JPanel(BorderLayout()).apply {
                isOpaque = false
                if (projects.model.size > 0) add(Separator(), BorderLayout.NORTH)
                add(commands, BorderLayout.CENTER)
            }, BorderLayout.SOUTH)
        }
        for (list in listOf(projects, commands)) {
            list.selectionModel.addListSelectionListener {
                if (list.selectedIndex >= 0) other(list).clearSelection()
            }
            bindKeys(list)
            val mouse = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    rowAt(list, e)?.let { if (list.selectedIndex != it) list.selectedIndex = it }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) rowAt(list, e)?.let { choose(list.model.getElementAt(it), e) }
                }
            }
            list.addMouseListener(mouse)
            list.addMouseMotionListener(mouse)
        }
        val focused = if (projects.model.size > 0) projects else commands
        focused.selectedIndex = 0
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, focused)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setMovable(false)
            .setResizable(false)
            .createPopup()
    }

    fun showUnderneathOf(anchor: Component) {
        this.anchor = anchor
        popup.showUnderneathOf(anchor)
    }

    /** A branch came in: it widens its row, and the list's UI caches cell sizes until a fixed height comes and goes. */
    fun branchesChanged() {
        if (popup.isDisposed) return
        val fixed = projects.fixedCellHeight
        projects.fixedCellHeight = 1
        projects.fixedCellHeight = fixed
        projects.revalidate()
        projects.repaint()
        popup.pack(true, false)
    }

    private fun choose(item: ActionItem, event: InputEvent?) {
        val next = step.onChosen(item, true, event)
        popup.setFinalRunnable(step.finalRunnable)
        popup.closeOk(event)
        // An action group among the stock actions opens as its own popup where this one was.
        if (next != null && next !== PopupStep.FINAL_CHOICE && next is ListPopupStep<*>) {
            anchor?.let { JBPopupFactory.getInstance().createListPopup(next).showUnderneathOf(it) }
        }
    }

    /** Up or Down through both lists as one, wrapping around at either end. */
    internal fun move(delta: Int) {
        val rows = (0 until projects.model.size).map { projects to it } + (0 until commands.model.size).map { commands to it }
        if (rows.isEmpty()) return
        val current = rows.indexOfFirst { (list, index) -> list.selectedIndex == index }
        val next = if (current < 0) (if (delta > 0) 0 else rows.size - 1) else (current + delta).mod(rows.size)
        val (list, index) = rows[next]
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
    }

    private fun selected(): ActionItem? = projects.selectedValue ?: commands.selectedValue

    private fun bindKeys(list: JList<ActionItem>) {
        fun bind(key: Int, name: String, run: () -> Unit) {
            list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name)
            list.actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = run()
            })
        }
        bind(KeyEvent.VK_UP, "agenstorm.previous") { move(-1) }
        bind(KeyEvent.VK_DOWN, "agenstorm.next") { move(1) }
        bind(KeyEvent.VK_ENTER, "agenstorm.choose") { selected()?.let { choose(it, null) } }
    }

    private fun other(list: JList<ActionItem>) = if (list === projects) commands else projects

    private fun rowAt(list: JList<ActionItem>, e: MouseEvent): Int? {
        val index = list.locationToIndex(e.point)
        return index.takeIf { it >= 0 && list.getCellBounds(it, it)?.contains(e.point) == true }
    }

    private fun list(items: List<ActionItem>, renderer: ListCellRenderer<in ActionItem>) = JBList(items).apply {
        cellRenderer = renderer
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        background = JBUI.CurrentTheme.Popup.BACKGROUND
        border = JBUI.Borders.empty(VERTICAL_PADDING, 0)
        setExpandableItemsEnabled(false)
    }

    /** The line between the projects and the actions, as the platform's popups draw one. */
    private class Separator : JComponent() {
        private val gap = JBUI.CurrentTheme.Popup.separatorInsets()

        override fun getPreferredSize() = Dimension(0, gap.top + gap.bottom + 1)

        override fun paintComponent(g: Graphics) {
            g.color = JBUI.CurrentTheme.Popup.separatorColor()
            g.fillRect(gap.left, gap.top, width - gap.left - gap.right, 1)
        }
    }

    companion object {
        /** Rows of recent projects shown before the list scrolls. */
        const val MAX_VISIBLE_PROJECTS = 12

        private const val VERTICAL_PADDING = 4

        /** One of the stock actions: its icon and text, as the platform's popup rows draw them. */
        fun command(text: String, icon: Icon?, isSelected: Boolean): JComponent {
            val label = JBLabel(text, icon, SwingConstants.LEFT).apply {
                iconTextGap = JBUI.scale(6)
                foreground = if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()
            }
            return SelectablePanel.wrap(label, JBUI.CurrentTheme.Popup.BACKGROUND).apply {
                PopupUtil.configListRendererFixedHeight(this)
                if (isSelected) selectionColor = UIUtil.getListSelectionBackground(true)
            }
        }
    }
}
