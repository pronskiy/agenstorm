package com.pronskiy.agenstorm.markdown.toc

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JLayeredPane
import javax.swing.JPanel

/**
 * Step W2.8 (decision 98). The widget's own layer in the editor's layered pane, the pane `EditorImpl` keeps its scroll
 * pane, sticky lines, scrollbar and inspection widget in. That pane lays out any other child as the inspection widget:
 * at the top-right edge with its preferred size, and the scrollbar moved to start at that size's height, keeping its
 * own height minus the move. So this layer reports the scrollbar's current top as its preferred height — the pane's
 * check then finds nothing to move (a height of 0 would move the scrollbar up, and the inspection widget, laid out
 * next, would push it down and shorten it, a little more on every layout) — and whenever it is laid out takes its
 * bounds from [TocPlacement] instead: against the scrollbar, below the inspection widget. Its layer is above that
 * widget's, so the pane lays it out first and the inspection widget keeps the last word on where the scrollbar starts.
 */
class TocLayer private constructor(private val controller: TocController) : JPanel(BorderLayout()) {

    internal val panel = TocPanel(controller)
    private var subscription: Disposable? = null

    init {
        isOpaque = false
        add(panel, BorderLayout.CENTER)
    }

    override fun getPreferredSize(): Dimension = Dimension(0, controller.editor.scrollPane.verticalScrollBar?.y ?: 0)

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        val pane = parent ?: return super.setBounds(x, y, width, height)
        val size = panel.preferredSize
        val bounds = TocPlacement.bounds(pane.width, scrollbarWidth(), statusHeight(), size.width, size.height, JBUI.scale(TOP_GAP))
        super.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
    }

    /** Shown while the controller says so; the panel gets the state and the viewport. EDT. */
    private fun refresh() {
        if (controller.isDisposed) return
        val state = controller.state
        isVisible = state.shows
        if (state.shows) panel.show(state, controller.viewport())
    }

    private fun scrollbarWidth(): Int {
        val scrollbar = controller.editor.scrollPane.verticalScrollBar ?: return 0
        return if (scrollbar.isVisible) scrollbar.width else 0
    }

    /** The inspection widget's height, from its preferred size as the pane itself lays it out; 0 when there is none. */
    private fun statusHeight(): Int {
        val status = (controller.editor.scrollPane as? JBScrollPane)?.statusComponent ?: return 0
        return if (status.isVisible) status.preferredSize.height else 0
    }

    private fun uninstall() {
        subscription?.let(Disposer::dispose)
        subscription = null
        val pane = parent ?: return
        pane.remove(this)
        pane.revalidate()
        pane.repaint()
    }

    companion object {
        /** Above `EditorImpl`'s inspection widget and floating toolbar (both 300): laid out before them, painted over them. */
        private const val LAYER = 400
        private const val TOP_GAP = 4

        /** Puts [controller]'s widget in its editor's layered pane, taken out again with the controller. EDT. */
        fun install(controller: TocController): TocLayer? {
            val pane = controller.editor.scrollPane.parent as? JLayeredPane ?: return null
            val layer = TocLayer(controller)
            JLayeredPane.putLayer(layer, LAYER)
            pane.add(layer)
            layer.subscription = controller.subscribe(layer::refresh)
            Disposer.register(controller, Disposable { layer.uninstall() })
            layer.refresh()
            pane.revalidate()
            return layer
        }
    }
}
