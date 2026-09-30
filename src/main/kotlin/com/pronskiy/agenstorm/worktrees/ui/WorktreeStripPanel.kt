package com.pronskiy.agenstorm.worktrees.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.worktrees.WorktreeTab
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Steps T1.4–T1.5. The worktree strip above the Project tree (decision 82): one tab per worktree, the current one
 * filled, "+" at the end, and "»" with whatever does not fit ([StripLayout] decides, and the current tab always shows).
 * A click switches, a right click opens the tab's menu, and a line under the row separates it from the tree. Hidden
 * while there are no tabs.
 */
class WorktreeStripPanel(
    private val onSelect: (WorktreeTab) -> Unit,
    private val onAdd: () -> Unit,
    private val onContextMenu: (WorktreeTab, Component, Point) -> Unit,
) : JPanel(null) {

    private var tabs: List<WorktreeTab> = emptyList()
    private var labels: List<TabLabel> = emptyList()
    private var overflow: List<WorktreeTab> = emptyList()

    private val addButton = iconButton(AllIcons.General.Add, AgenstormBundle.message("worktrees.strip.add.tooltip")) { onAdd() }
    private val moreButton = iconButton(AllIcons.General.ChevronDown, AgenstormBundle.message("worktrees.strip.more.tooltip"), ::showOverflow)

    init {
        isOpaque = false
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(3, 6))
        isVisible = false
    }

    fun show(tabs: List<WorktreeTab>) {
        this.tabs = tabs
        removeAll()
        labels = tabs.map(::TabLabel)
        labels.forEach(::add)
        add(addButton)
        add(moreButton)
        isVisible = tabs.isNotEmpty()
        revalidate()
        repaint()
    }

    /** The tabs the last layout left out, in order: what "»" lists. */
    internal fun overflowTabs(): List<WorktreeTab> = overflow

    internal fun tabLabels(): List<JBLabel> = labels

    internal fun moreButton(): JComponent = moreButton

    override fun getPreferredSize(): Dimension {
        val insets = insets
        val height = (labels + addButton).maxOf { it.preferredSize.height }
        val width = labels.sumOf { it.preferredSize.width + GAP } + addButton.preferredSize.width
        return Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom)
    }

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    override fun doLayout() {
        val insets = insets
        val height = height - insets.top - insets.bottom
        val plan = StripLayout.plan(
            widths = labels.map { it.preferredSize.width },
            current = tabs.indexOfFirst { it.isCurrent },
            available = width - insets.left - insets.right,
            gap = GAP,
            fixed = addButton.preferredSize.width,
            more = moreButton.preferredSize.width + GAP,
        )
        overflow = plan.overflow.map(tabs::get)
        var x = insets.left
        labels.forEachIndexed { index, label ->
            label.isVisible = index in plan.visible
            if (label.isVisible) {
                val width = label.preferredSize.width
                label.setBounds(x, insets.top, width, height)
                x += width + GAP
            }
        }
        moreButton.isVisible = overflow.isNotEmpty()
        if (moreButton.isVisible) {
            moreButton.setBounds(x, insets.top, moreButton.preferredSize.width, height)
            x += moreButton.preferredSize.width + GAP
        }
        addButton.setBounds(x, insets.top, addButton.preferredSize.width, height)
    }

    private fun showOverflow(anchor: JComponent) {
        val group = DefaultActionGroup(overflow.map { tab ->
            object : DumbAwareAction(tab.label) {
                override fun actionPerformed(e: AnActionEvent) = onSelect(tab)
            }
        })
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(anchor), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
            .showUnderneathOf(anchor)
    }

    private fun iconButton(icon: Icon, tooltip: String, onClick: (JComponent) -> Unit): JBLabel = JBLabel(icon).apply {
        border = JBUI.Borders.empty(3, 6)
        toolTipText = tooltip
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) onClick(this@apply)
            }
        })
    }

    private inner class TabLabel(private val tab: WorktreeTab) : JBLabel(tab.label) {

        private var hovered = false

        init {
            border = JBUI.Borders.empty(3, 8)
            toolTipText = tab.worktree.branch?.let { AgenstormBundle.message("worktrees.strip.tab.tooltip", tab.path, it) } ?: tab.path
            if (tab.isCurrent) font = font.deriveFont(Font.BOLD) else cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (!e.isPopupTrigger && e.button == MouseEvent.BUTTON1 && !tab.isCurrent) onSelect(tab)
                }

                override fun mouseEntered(e: MouseEvent) = setHovered(true)

                override fun mouseExited(e: MouseEvent) = setHovered(false)
            })
            // Our own PopupHandler, so one a parent installs on components that have none never claims the right click.
            addMouseListener(object : PopupHandler() {
                override fun invokePopup(comp: Component, x: Int, y: Int) = onContextMenu(tab, comp, Point(x, y))
            })
        }

        private fun setHovered(value: Boolean) {
            if (hovered == value) return
            hovered = value
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val fill = when {
                tab.isCurrent -> JBUI.CurrentTheme.ActionButton.pressedBackground()
                hovered -> JBUI.CurrentTheme.ActionButton.hoverBackground()
                else -> null
            }
            if (fill != null) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = fill
                    val arc = JBUI.scale(8)
                    g2.fillRoundRect(0, 0, width, height, arc, arc)
                } finally {
                    g2.dispose()
                }
            }
            super.paintComponent(g)
        }
    }

    companion object {
        private val GAP get() = JBUI.scale(2)
    }
}
