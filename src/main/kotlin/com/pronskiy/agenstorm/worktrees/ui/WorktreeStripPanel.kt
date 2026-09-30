package com.pronskiy.agenstorm.worktrees.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
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
 * while there are no tabs. T3.3: a tab's [TabBadge] is painted after its name — the dot in the IDE's "modified"
 * colour, the rest dimmed — and its lines join the tooltip; the label's text stays the name.
 */
class WorktreeStripPanel(
    private val onSelect: (WorktreeTab) -> Unit,
    private val onAdd: () -> Unit,
    private val onContextMenu: (WorktreeTab, Component, Point) -> Unit,
) : JPanel(null) {

    private var tabs: List<WorktreeTab> = emptyList()
    private var badges: Map<String, TabBadge> = emptyMap()
    private var labels: List<TabLabel> = emptyList()
    private var overflow: List<WorktreeTab> = emptyList()

    private val addButton = iconButton(AllIcons.General.Add, AgenstormBundle.message("worktrees.strip.add.tooltip")) { onAdd() }
    private val moreButton = iconButton(AllIcons.General.ChevronDown, AgenstormBundle.message("worktrees.strip.more.tooltip"), ::showOverflow)

    init {
        isOpaque = false
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(3, 6))
        isVisible = false
    }

    fun show(tabs: List<WorktreeTab>, badges: Map<String, TabBadge> = emptyMap()) {
        if (tabs == this.tabs && badges == this.badges && componentCount > 0) return
        this.tabs = tabs
        this.badges = badges
        removeAll()
        labels = tabs.map { TabLabel(it, badges[it.path] ?: TabBadge.NONE) }
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

    internal fun badgeOf(label: JBLabel): TabBadge? = (label as? TabLabel)?.badge

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

    private inner class TabLabel(private val tab: WorktreeTab, val badge: TabBadge) : JBLabel(tab.label) {

        private var hovered = false

        init {
            border = JBUI.Borders.empty(3, 8)
            val title = tab.worktree.branch?.let { AgenstormBundle.message("worktrees.strip.tab.tooltip", tab.path, it) } ?: tab.path
            toolTipText = if (badge.tooltip.isEmpty()) title else tooltip(title, badge.tooltip)
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

        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            if (badge.parts.isEmpty()) return size
            return Dimension(size.width + BADGE_GAP + getFontMetrics(font).stringWidth(badge.text), size.height)
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
            if (badge.parts.isNotEmpty()) paintBadge(g)
        }

        /** Where `BasicLabelUI` puts a left-aligned, vertically centred text, then the badge after it. */
        private fun paintBadge(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                UISettings.setupAntialiasing(g2)
                g2.font = font
                val metrics = g2.fontMetrics
                val insets = insets
                var x = insets.left + metrics.stringWidth(text) + BADGE_GAP
                val y = insets.top + (height - insets.top - insets.bottom - metrics.height) / 2 + metrics.ascent
                for (part in badge.parts) {
                    g2.color = if (part.kind == TabBadge.Kind.DIRTY) FileStatus.MODIFIED.color ?: foreground else UIUtil.getContextHelpForeground()
                    g2.drawString(part.text, x, y)
                    x += metrics.stringWidth(part.text + " ")
                }
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        private val GAP get() = JBUI.scale(2)
        private val BADGE_GAP get() = JBUI.scale(5)

        private fun tooltip(title: String, lines: List<String>): String =
            HtmlBuilder().append(title).apply { lines.forEach { br().append(it) } }.wrapWithHtmlBody().toString()
    }
}
