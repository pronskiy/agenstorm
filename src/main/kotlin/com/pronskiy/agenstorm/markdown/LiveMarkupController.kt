package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.ThreadingAssertions
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Steps F1.2 / F1.3. Keeps one editor's "light" fold regions in step with [MarkupRangeCollector]: every marker range
 * gets a region whose placeholder is the marker's replacement (usually nothing), tagged with [KIND] so the controller
 * never touches anyone else's regions (the Markdown plugin folds headings and lists in the same editor).
 *
 * Collection runs in a background read action once the document is committed; the fold model is changed on the EDT
 * in one batch operation, and only if the document has not moved on in between (the next debounced sync handles that).
 * Regions are compared by offsets, kind and placeholder, so after an edit the regions that still fit are kept — fold
 * regions are range markers and follow the text — and only the difference is removed or created. Document changes
 * are debounced ([DEBOUNCE_MS]); the first sync runs at once so a freshly opened file does not flash raw markup.
 *
 * Caret policy (F1.3): regions on a line that holds a caret, or intersecting a selection, are expanded so the raw
 * Markdown is there to edit and what is selected is what gets copied; every other region is collapsed. The policy is
 * part of every sync and is re-applied, coalesced through `invokeLater`, when a caret changes line, carets are added
 * or removed, or the selection changes. Moving within a line does nothing, so typing costs no fold operations.
 *
 * Light regions are created with `FoldingModelEx.createFoldRegion` rather than through a `FoldingBuilder`: builder
 * regions shorter than two characters are dropped, ours are often one character long (SPEC.md decision 10).
 */
@OptIn(FlowPreview::class)
class LiveMarkupController(
    private val editor: EditorEx,
    private val project: Project,
    scope: CoroutineScope,
    debounceMs: Long = DEBOUNCE_MS,
) : Disposable {

    private val resync = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val job: Job
    private var policyScheduled = false

    init {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                resync.tryEmit(Unit)
            }
        }, this)
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (event.oldPosition.line != event.newPosition.line) scheduleCaretPolicy()
            }

            override fun caretAdded(event: CaretEvent) = scheduleCaretPolicy()

            override fun caretRemoved(event: CaretEvent) = scheduleCaretPolicy()
        }, this)
        editor.selectionModel.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) = scheduleCaretPolicy()
        }, this)
        job = scope.launch(CoroutineName("Agenstorm live markup")) {
            sync()
            resync.debounce(debounceMs).collectLatest { sync() }
        }
    }

    /** Asks for a re-sync after the debounce period, as a document change would. */
    fun requestSync() {
        resync.tryEmit(Unit)
    }

    /** Collects off the EDT (after pending PSI commits), applies on the EDT. */
    suspend fun sync() {
        val snapshot = constrainedReadAction(ReadConstraint.withDocumentsCommitted(project)) { collect() } ?: return
        withContext(Dispatchers.EDT) { apply(snapshot) }
    }

    /** Synchronous variant for callers already on the EDT with committed documents (tests, the toggle action). */
    fun syncNow() {
        ThreadingAssertions.assertEventDispatchThread()
        collect()?.let(::apply)
    }

    /** Every region this controller created and that is still valid. */
    fun regions(): List<FoldRegion> = editor.foldingModel.allFoldRegions.filter { it.isValid && it.getUserData(KIND) != null }

    /**
     * Expands our regions on caret lines and under selections, collapses the rest; one batch, skipped entirely when
     * nothing would change.
     */
    fun applyCaretPolicy() {
        ThreadingAssertions.assertEventDispatchThread()
        if (editor.isDisposed) return
        val revealed = revealedRanges()
        val changes = regions().filter { it.isExpanded != isRevealed(it.startOffset, it.endOffset, revealed) }
        if (changes.isEmpty()) return
        editor.foldingModel.runBatchFoldingOperation({
            for (region in changes) region.isExpanded = !region.isExpanded
        }, false, true)
    }

    /** Removes every region of ours in one batch; the Markdown plugin's regions stay. */
    fun removeAll() {
        if (editor.isDisposed || !ApplicationManager.getApplication().isDispatchThread) return
        val model = editor.foldingModel
        val ours = regions()
        if (ours.isEmpty()) return
        model.runBatchFoldingOperation({ ours.forEach(model::removeFoldRegion) }, false, true)
    }

    override fun dispose() {
        job.cancel()
        removeAll()
    }

    private class Snapshot(val ranges: List<MarkupRange>, val stamp: Long)

    private data class RegionKey(val start: Int, val end: Int, val kind: MarkupKind, val placeholder: String)

    /** Read access required. */
    private fun collect(): Snapshot? {
        if (editor.isDisposed || project.isDisposed) return null
        val document = editor.document
        val file = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return null
        val started = System.nanoTime()
        val ranges = MarkupRangeCollector.collect(file)
        if (LOG.isDebugEnabled) LOG.debug("live markup: ${ranges.size} ranges collected in ${(System.nanoTime() - started) / 1_000_000} ms")
        return Snapshot(ranges, document.modificationStamp)
    }

    private fun apply(snapshot: Snapshot) {
        ThreadingAssertions.assertEventDispatchThread()
        if (editor.isDisposed || editor.document.modificationStamp != snapshot.stamp) return
        val started = System.nanoTime()
        val wanted = LinkedHashMap<RegionKey, MarkupRange>()
        for (range in snapshot.ranges) wanted[RegionKey(range.range.startOffset, range.range.endOffset, range.kind, range.placeholder)] = range
        val revealed = revealedRanges()
        val model = editor.foldingModel
        var removed = 0
        var created = 0
        model.runBatchFoldingOperation({
            for (region in model.allFoldRegions) {
                val kind = region.getUserData(KIND) ?: continue
                if (!region.isValid) continue
                if (wanted.remove(RegionKey(region.startOffset, region.endOffset, kind, region.placeholderText)) != null) {
                    val expanded = isRevealed(region.startOffset, region.endOffset, revealed)
                    if (region.isExpanded != expanded) region.isExpanded = expanded
                    continue
                }
                model.removeFoldRegion(region)
                removed++
            }
            for (range in wanted.values) {
                val region = model.createFoldRegion(range.range.startOffset, range.range.endOffset, range.placeholder, null, false) ?: continue
                region.putUserData(KIND, range.kind)
                region.setGutterMarkEnabledForSingleLine(false)
                region.isExpanded = isRevealed(range.range.startOffset, range.range.endOffset, revealed)
                created++
            }
        }, false, true)
        if (LOG.isDebugEnabled) LOG.debug("live markup: $created regions created, $removed removed in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    /** Caret lines (whole) and selections, for every caret. */
    private fun revealedRanges(): List<TextRange> {
        val document = editor.document
        val out = ArrayList<TextRange>()
        for (caret in editor.caretModel.allCarets) {
            val line = document.getLineNumber(caret.offset.coerceIn(0, document.textLength))
            out += TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
            if (caret.hasSelection()) out += TextRange(caret.selectionStart, caret.selectionEnd)
        }
        return out
    }

    private fun scheduleCaretPolicy() {
        if (policyScheduled || editor.isDisposed) return
        policyScheduled = true
        ApplicationManager.getApplication().invokeLater({
            policyScheduled = false
            applyCaretPolicy()
        }, Condition<Any?> { editor.isDisposed })
    }

    companion object {
        private val LOG = logger<LiveMarkupController>()
        const val DEBOUNCE_MS = 200L
        /** Marks a fold region as ours and says what it hides. */
        val KIND: Key<MarkupKind> = Key.create("agenstorm.liveMarkup.kind")

        /** A region `[start, end)` is revealed when it overlaps one of [revealed]; touching at an edge is not overlapping. */
        fun isRevealed(start: Int, end: Int, revealed: List<TextRange>): Boolean =
            revealed.any { start < it.endOffset && end > it.startOffset }
    }
}
