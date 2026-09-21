package com.pronskiy.agenstorm.markdown.tables

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.ui.scale.JBUIScale
import com.pronskiy.agenstorm.markdown.LiveMarkupController
import com.pronskiy.agenstorm.markdown.MarkupKind
import java.awt.Graphics2D
import java.awt.Point
import java.awt.geom.Rectangle2D

/**
 * Step Q2.3. The rendered table itself: a block inlay's renderer that lays the [model] out for the width the
 * editor offers, caches the geometry until the width, the font or the colours change, and paints it with
 * [TablePainter]. [indent] is the pixel width of the table's own indentation (a table nested in a list or a
 * quote), so the block sits where its first pipe would.
 */
class TableInlayRenderer(private val editor: EditorEx, model: TableModel, private val indent: Int) : EditorCustomElementRenderer {

    @Volatile
    var model: TableModel = model
        private set

    private class Cache(val width: Int, val key: List<Any?>, val measurer: FontMetricsMeasurer, val palette: TablePalette, val geometry: TableGeometry)

    private var cache: Cache? = null

    /** A model with the same content keeps the cached geometry; only the offsets it carries are refreshed. */
    fun update(model: TableModel) {
        if (!this.model.sameContent(model)) cache = null
        this.model = model
    }

    fun geometry(): TableGeometry = current().geometry

    fun measurer(): FontMetricsMeasurer = current().measurer

    fun palette(): TablePalette = current().palette

    override fun calcWidthInPixels(inlay: Inlay<*>): Int = indent + geometry().width

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = geometry().height + 2 * margin()

    override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) {
        val current = current()
        TablePainter.paint(g, current.geometry, targetRegion.x.toInt() + indent, targetRegion.y.toInt() + margin(), current.measurer, current.palette)
    }

    /** What is under [point], given in the editor content component's coordinates; null off the table. */
    fun hitTest(inlay: Inlay<*>, point: Point): Hit? {
        val bounds = inlay.bounds ?: return null
        return geometry().hitTest(point.x - bounds.x - indent, point.y - bounds.y - margin())
    }

    private fun current(): Cache {
        val width = availableWidth()
        val scheme = editor.colorsScheme
        val key = listOf(scheme.editorFontSize2D, scheme.editorFontName, scheme.defaultForeground, scheme.defaultBackground, JBUIScale.scale(1f))
        cache?.takeIf { it.width == width && it.key == key }?.let { return it }
        val measurer = FontMetricsMeasurer(editor.contentComponent, TableFonts.of(scheme))
        val geometry = TableLayout.layout(model, width, measurer, Padding(JBUIScale.scale(HORIZONTAL_PADDING), JBUIScale.scale(VERTICAL_PADDING)))
        return Cache(width, key, measurer, TablePalette.of(scheme), geometry).also { cache = it }
    }

    /** The viewport minus the editor's insets and the table's own indent; a fixed width before the editor is shown. */
    private fun availableWidth(): Int {
        val visible = editor.scrollingModel.visibleArea.width
        val viewport = if (visible > 0) visible else DEFAULT_WIDTH
        return maxOf(MIN_WIDTH, viewport - editor.insets.left - editor.insets.right - indent - JBUIScale.scale(RIGHT_MARGIN))
    }

    private fun margin(): Int = JBUIScale.scale(MARGIN)

    companion object {
        const val HORIZONTAL_PADDING = 12
        const val VERTICAL_PADDING = 6
        const val MARGIN = 4
        const val RIGHT_MARGIN = 16
        const val DEFAULT_WIDTH = 800
        const val MIN_WIDTH = 120
    }
}

/**
 * One block inlay per collapsed table region, owned by [LiveMarkupController] the way its block renderer is:
 * [sync] after every collector run, [refresh] after every caret-policy pass. The inlay hangs off the region's
 * start offset — the line break before the table, i.e. the end of the line above — shown below that line and
 * kept visible while the offset is folded. It is gone the moment the table is revealed and back when it collapses.
 */
class TableInlays(private val editor: EditorEx) : Disposable {

    private class Shown(val inlay: Inlay<TableInlayRenderer>, var model: TableModel)

    private var shown = ArrayList<Shown>()
    private var tables: List<TableModel> = emptyList()
    private var updateScheduled = false

    init {
        editor.scrollingModel.addVisibleAreaListener(VisibleAreaListener { event ->
            if (event.newRectangle.width > 0 && event.newRectangle.width != event.oldRectangle.width) scheduleUpdate()
        }, this)
    }

    /** EDT. [tables] is the collector's latest list; [regions] the controller's regions. */
    fun sync(tables: List<TableModel>, regions: List<FoldRegion>) {
        this.tables = tables
        refresh(regions)
    }

    /** EDT. One inlay for every collapsed table region, none for a revealed one, against the last synced tables. */
    fun refresh(regions: List<FoldRegion>) {
        if (editor.isDisposed) return
        val wanted = LinkedHashMap<Int, Pair<FoldRegion, TableModel>>()
        for (region in regions) {
            if (!region.isValid || region.isExpanded || region.getUserData(LiveMarkupController.KIND) != MarkupKind.TABLE) continue
            val model = tables.firstOrNull { it.span.endOffset == region.endOffset && it.span.startOffset >= region.startOffset } ?: continue
            wanted[region.startOffset] = region to model
        }
        val kept = ArrayList<Shown>()
        for (entry in shown) {
            val match = wanted.remove(entry.inlay.offset)?.takeIf { entry.inlay.isValid }
            if (match == null) {
                Disposer.dispose(entry.inlay)
                continue
            }
            entry.inlay.renderer.update(match.second)
            if (entry.model !== match.second) {
                entry.model = match.second
                entry.inlay.update()
            }
            kept += entry
        }
        for ((region, model) in wanted.values) {
            val renderer = TableInlayRenderer(editor, model, indentOf(model))
            val properties = InlayProperties().showAbove(false).relatesToPrecedingText(true).showWhenFolded(true)
            val inlay = editor.inlayModel.addBlockElement(region.startOffset, properties, renderer) ?: continue
            kept += Shown(inlay, model)
        }
        shown = kept
    }

    fun inlays(): List<Inlay<TableInlayRenderer>> = shown.map { it.inlay }.filter { it.isValid }

    /** The rendered table and what is under [point] (editor content coordinates), or null when none is there. */
    fun hitAt(point: Point): Pair<Inlay<TableInlayRenderer>, Hit>? {
        for (inlay in inlays()) {
            val hit = inlay.renderer.hitTest(inlay, point) ?: continue
            return inlay to hit
        }
        return null
    }

    fun removeAll() {
        shown.forEach { Disposer.dispose(it.inlay) }
        shown.clear()
    }

    override fun dispose() = removeAll()

    /** Pixels between the line start and the table's first pipe: a nested table keeps its indent. */
    private fun indentOf(model: TableModel): Int {
        val document = editor.document
        val start = model.span.startOffset.coerceIn(0, document.textLength)
        val columns = start - document.getLineStartOffset(document.getLineNumber(start))
        return columns * EditorUtil.getPlainSpaceWidth(editor)
    }

    /** A width change re-lays every table out once per event-loop turn, however many events the drag produced. */
    private fun scheduleUpdate() {
        if (updateScheduled || editor.isDisposed) return
        updateScheduled = true
        ApplicationManager.getApplication().invokeLater({
            updateScheduled = false
            if (!editor.isDisposed) inlays().forEach { it.update() }
        }) { editor.isDisposed }
    }
}
