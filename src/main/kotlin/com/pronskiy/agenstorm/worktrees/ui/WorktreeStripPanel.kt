package com.pronskiy.agenstorm.worktrees.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.worktrees.WorktreeTab
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/**
 * Step T1.4: the worktree strip above the Project tree, first cut — one label per tab, the current one filled, a "+" at
 * the end and a line under the row. T1.5 gives it overflow, a context menu and tests.
 */
class WorktreeStripPanel(
    private val onSelect: (WorktreeTab) -> Unit,
    private val onAdd: () -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)) {

    init {
        isOpaque = false
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(3, 6))
        isVisible = false
    }

    fun show(tabs: List<WorktreeTab>) {
        removeAll()
        tabs.forEach { add(TabLabel(it)) }
        add(JBLabel(AllIcons.General.Add).apply {
            border = JBUI.Borders.empty(3, 6)
            toolTipText = AgenstormBundle.message("worktrees.strip.add.tooltip")
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onAdd()
            })
        })
        isVisible = tabs.isNotEmpty()
        revalidate()
        repaint()
    }

    private inner class TabLabel(private val tab: WorktreeTab) : JBLabel(tab.label) {
        init {
            border = JBUI.Borders.empty(3, 8)
            toolTipText = tab.worktree.branch?.let { AgenstormBundle.message("worktrees.strip.tab.tooltip", tab.path, it) } ?: tab.path
            if (tab.isCurrent) font = font.deriveFont(Font.BOLD)
            if (!tab.isCurrent) cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (!tab.isCurrent) onSelect(tab)
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            if (tab.isCurrent) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = JBUI.CurrentTheme.ActionButton.pressedBackground()
                    val arc = JBUI.scale(8)
                    g2.fillRoundRect(0, 0, width, height, arc, arc)
                } finally {
                    g2.dispose()
                }
            }
            super.paintComponent(g)
        }
    }
}
