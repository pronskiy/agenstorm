package com.pronskiy.agenstorm.markdown.toc

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormBundle
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.min

/**
 * Step W2.3 (decision 97). The widget in the floating-toolbar slot: the card — a row per heading, indented by level,
 * the current one bold with an accent bar, at most [MAX_WIDTH] wide and half the editor high, scrolling inside — or,
 * when [TocFit] says the card would cover text, a pill that opens the card while the mouse is over it and closes
 * [CLOSE_DELAY_MS] after it leaves. One panel per editor toolbar, bound to its controller by [TocAction].
 */
class TocPanel : JPanel(BorderLayout()) {

    internal var mode = TocFit.Mode.CARD
        private set

    private var controller: TocController? = null
    private var subscription: Disposable? = null
    private var entries: List<TocEntry> = emptyList()
    private var current = -1
    private var hovered = -1
    private var minLevel = 1
    private var maxHeight = 0

    /** The pill's card is showing. */
    private var open = false

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

    private val pill = JBLabel(AllIcons.Toolwindows.ToolWindowStructure).apply {
        border = JBUI.Borders.empty(4)
        toolTipText = AgenstormBundle.message("markdown.toc.pill.tooltip")
    }

    private val closeTimer = Timer(CLOSE_DELAY_MS) { if (open && !isMouseInside()) setOpen(false) }.apply { isRepeats = false }

    init {
        isOpaque = false
        val mouse = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                closeTimer.stop()
                if (mode == TocFit.Mode.PILL && !open) setOpen(true)
            }

            override fun mouseExited(e: MouseEvent) {
                if (open) closeTimer.restart()
                setHovered(-1)
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.component === pill && !open) setOpen(true)
            }

            override fun mouseMoved(e: MouseEvent) {
                if (e.component === list) setHovered(rowAt(e.point))
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.component !== list || !SwingUtilities.isLeftMouseButton(e)) return
                val row = rowAt(e.point)
                if (row < 0) return
                val entry = entries[row]
                list.clearSelection()
                if (open) setOpen(false)
                controller?.navigate(entry)
            }
        }
        for (component in listOf(this, pill, list, scroll.viewport)) {
            component.addMouseListener(mouse)
            component.addMouseMotionListener(mouse)
        }
        relayout()
    }

    /** Follows [next]'s state from now on; null lets go. EDT. */
    fun bind(next: TocController?) {
        if (next === controller) return
        subscription?.let(Disposer::dispose)
        subscription = null
        controller = next
        if (next != null) {
            subscription = next.subscribe { show(next.state, next.viewport()) }
            show(next.state, next.viewport())
        }
    }

    /** Shows [state], as a card or a pill depending on [viewport]. EDT. */
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
        val rightInset = viewport.scrollbarWidth + TocFit.SLOT_EDGE + slotGap()
        val next = TocFit.mode(viewport.width, viewport.marginColumns, viewport.spaceWidth, cardWidth(), rightInset, JBUI.scale(TocFit.GAP))
        if (next != mode) {
            mode = next
            open = false
            relayout()
        } else if (resized) {
            revalidate()
        }
        if (current in entries.indices) list.ensureIndexIsVisible(current)
        list.repaint()
    }

    /** The rows' natural width, between [MIN_WIDTH] and [MAX_WIDTH]. */
    fun cardWidth(): Int = list.preferredSize.width.coerceIn(JBUI.scale(MIN_WIDTH), JBUI.scale(MAX_WIDTH))

    override fun getPreferredSize(): Dimension {
        if (mode == TocFit.Mode.PILL && !open) return super.getPreferredSize()
        val rows = list.preferredSize.height
        val height = if (maxHeight > 0) min(rows, maxHeight) else rows
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

    private fun setOpen(value: Boolean) {
        open = value
        relayout()
    }

    private fun relayout() {
        removeAll()
        if (mode == TocFit.Mode.CARD || open) {
            border = JBUI.Borders.empty(6, 0)
            add(scroll, BorderLayout.CENTER)
        } else {
            border = JBUI.Borders.empty()
            add(pill, BorderLayout.CENTER)
        }
        revalidate()
        repaint()
    }

    /** The horizontal gap of the floating-toolbar slot's `FlowLayout`, the first one above this panel. */
    private fun slotGap(): Int {
        var container: Container? = parent
        while (container != null) {
            (container.layout as? FlowLayout)?.let { return it.hgap }
            container = container.parent
        }
        return TocFit.SLOT_GAP
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

    private fun isMouseInside(): Boolean {
        if (!isShowing) return false
        val pointer = MouseInfo.getPointerInfo()?.location ?: return false
        SwingUtilities.convertPointFromScreen(pointer, this)
        return contains(pointer)
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
        const val MAX_WIDTH = 260
        const val MIN_WIDTH = 120
        const val CLOSE_DELAY_MS = 300
        private const val PADDING = 10
        private const val INDENT = 12
    }
}
