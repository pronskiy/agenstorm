package com.pronskiy.agenstorm.markdown.toc

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import kotlin.math.min

/**
 * Steps W2.3, W2.9, W2.10 (decisions 97, 99). The widget: the card — a row per heading, indented by level, the current
 * one bold with an accent bar, at most [MAX_WIDTH] wide and half the editor high, scrolling inside — or the card folded
 * into its icon. The icon sits in the top-right corner either way, and a click on it folds or unfolds the card in
 * every Markdown editor, remembered; until the first click [TocFit]'s width rule decides. Shown by [TocLayer].
 */
class TocPanel(private val controller: TocController?) : JPanel(BorderLayout()) {

    /** What W1.3's width rule says; whether the card shows also depends on the user's choice, see [folded]. */
    internal var mode = TocFit.Mode.CARD
        private set

    /** The card is folded into the icon: the user's choice, or the width rule while there is none. */
    internal val folded: Boolean
        get() = TocFit.folded(AgenstormSettings.getInstance().state.markdownTocFold, mode)

    /** What [relayout] last built, so a change of [folded] rebuilds. */
    private var builtFolded: Boolean? = null
    private var entries: List<TocEntry> = emptyList()
    private var current = -1
    private var hovered = -1
    private var minLevel = 1
    private var maxHeight = 0

    private val list = object : JBList<TocEntry>() {
        // Rows as wide as the card, so a long title ends in an ellipsis instead of being cut off by the viewport.
        override fun getScrollableTracksViewportWidth() = true
    }.apply {
        isOpaque = false
        isFocusable = false
        cellRenderer = Renderer()
    }

    private val scroll = JBScrollPane(list).apply {
        border = JBUI.Borders.empty()
        viewportBorder = null
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }

    internal val foldIcon = JBLabel(AllIcons.Toolwindows.ToolWindowStructure).apply {
        border = JBUI.Borders.empty(4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = AgenstormBundle.message("markdown.toc.fold.tooltip")
    }

    /** The card's top row: the icon on the right, nothing else. */
    private val header = JPanel(BorderLayout()).apply { isOpaque = false }

    init {
        isOpaque = false
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.component === foldIcon && SwingUtilities.isLeftMouseButton(e)) toggleFold()
            }

            override fun mouseExited(e: MouseEvent) = setHovered(-1)

            override fun mouseMoved(e: MouseEvent) {
                if (e.component === list) setHovered(rowAt(e.point))
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.component !== list || !SwingUtilities.isLeftMouseButton(e)) return
                val row = rowAt(e.point)
                if (row < 0) return
                list.clearSelection()
                controller?.navigate(entries[row])
            }
        }
        for (component in listOf(foldIcon, list)) {
            component.addMouseListener(mouse)
            component.addMouseMotionListener(mouse)
        }
        relayout()
    }

    /** Shows [state]; [viewport] feeds the width rule and the height cap. EDT. */
    fun show(state: TocState, viewport: TocController.Viewport) {
        var resized = false
        if (state.visible != entries) {
            entries = state.visible
            minLevel = entries.minOfOrNull { it.level } ?: 1
            list.setListData(entries.toTypedArray())
            resized = true
        }
        current = state.current
        if (viewport.height / 2 != maxHeight) {
            maxHeight = viewport.height / 2
            resized = true
        }
        mode = TocFit.mode(viewport.width, viewport.marginColumns, viewport.spaceWidth, cardWidth(), viewport.scrollbarWidth, JBUI.scale(TocFit.GAP))
        if (folded != builtFolded) relayout() else if (resized) revalidate()
        if (!folded && current in entries.indices) list.ensureIndexIsVisible(current)
        list.repaint()
    }

    /** The icon's click: the opposite of what shows now, for every Markdown editor, and remembered (decision 99). */
    internal fun toggleFold() {
        AgenstormSettings.getInstance().state.markdownTocFold = if (folded) TocFit.FOLD_UNFOLDED else TocFit.FOLD_FOLDED
        relayout()
        AgenstormSettingsListener.fire()
    }

    /** The rows' natural width, between [MIN_WIDTH] and [MAX_WIDTH]. */
    fun cardWidth(): Int = list.preferredSize.width.coerceIn(JBUI.scale(MIN_WIDTH), JBUI.scale(MAX_WIDTH))

    override fun getPreferredSize(): Dimension {
        if (folded) return super.getPreferredSize()
        val top = header.preferredSize.height
        val rows = list.preferredSize.height
        val height = if (maxHeight > 0) top + min(rows, (maxHeight - top).coerceAtLeast(0)) else top + rows
        val insets = insets
        return Dimension(cardWidth() + insets.left + insets.right, height + insets.top + insets.bottom)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val arc = JBUI.scale(10)
            g2.color = controller?.editor?.colorsScheme?.defaultBackground ?: UIUtil.getPanelBackground()
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
    }

    private fun relayout() {
        val fold = folded
        builtFolded = fold
        removeAll()
        header.removeAll()
        if (fold) {
            border = JBUI.Borders.empty()
            add(foldIcon, BorderLayout.CENTER)
        } else {
            border = JBUI.Borders.emptyBottom(6)
            header.add(foldIcon, BorderLayout.EAST)
            add(header, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }
        revalidate()
        repaint()
    }

    private fun setHovered(row: Int) {
        if (row == hovered) return
        hovered = row
        list.repaint()
    }

    private fun rowAt(point: Point): Int {
        val row = list.locationToIndex(point)
        return if (row >= 0 && list.getCellBounds(row, row)?.contains(point) == true) row else -1
    }

    /** A row: indented by level, muted unless current or hovered, the current one bold with an accent bar. */
    private inner class Renderer : ListCellRenderer<TocEntry> {
        private val label = RowLabel()

        override fun getListCellRendererComponent(list: JList<out TocEntry>, value: TocEntry, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
            val isCurrent = index == current
            label.text = value.title
            label.accent = isCurrent
            label.font = if (isCurrent) list.font.deriveFont(Font.BOLD) else list.font
            label.foreground = if (isCurrent || index == hovered) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
            label.border = JBUI.Borders.empty(2, PADDING + (value.level - minLevel) * INDENT, 2, PADDING)
            return label
        }
    }

    private class RowLabel : JBLabel() {
        var accent = false

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (!accent) return
            g.color = JBUI.CurrentTheme.Link.Foreground.ENABLED
            g.fillRect(0, JBUI.scale(2), JBUI.scale(2), height - JBUI.scale(4))
        }
    }

    companion object {
        const val MAX_WIDTH = 200
        const val MIN_WIDTH = 120
        private const val PADDING = 10
        private const val INDENT = 12
    }
}
