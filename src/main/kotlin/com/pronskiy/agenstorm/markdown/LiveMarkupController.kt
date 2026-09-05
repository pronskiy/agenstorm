package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
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
 * Step F1.2. Keeps one editor's "light" fold regions in step with [MarkupRangeCollector]: every marker range gets a
 * collapsed region whose placeholder is the marker's replacement (usually nothing), tagged with [KIND] so the
 * controller never touches anyone else's regions (the Markdown plugin folds headings and lists in the same editor).
 *
 * Collection runs in a background read action once the document is committed; the fold model is changed on the EDT
 * in one batch operation, and only if the document has not moved on in between (the next debounced sync handles that).
 * Regions are compared by offsets, kind and placeholder, so after an edit the regions that still fit are kept — fold
 * regions are range markers and follow the text — and only the difference is removed or created. Document changes
 * are debounced ([DEBOUNCE_MS]); the first sync runs at once so a freshly opened file does not flash raw markup.
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

    init {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                resync.tryEmit(Unit)
            }
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
        val model = editor.foldingModel
        var removed = 0
        var created = 0
        model.runBatchFoldingOperation({
            for (region in model.allFoldRegions) {
                val kind = region.getUserData(KIND) ?: continue
                if (!region.isValid) continue
                if (wanted.remove(RegionKey(region.startOffset, region.endOffset, kind, region.placeholderText)) != null) continue
                model.removeFoldRegion(region)
                removed++
            }
            for (range in wanted.values) {
                val region = model.createFoldRegion(range.range.startOffset, range.range.endOffset, range.placeholder, null, false) ?: continue
                region.putUserData(KIND, range.kind)
                region.setGutterMarkEnabledForSingleLine(false)
                region.isExpanded = false
                created++
            }
        }, false, true)
        if (LOG.isDebugEnabled) LOG.debug("live markup: $created regions created, $removed removed in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    companion object {
        private val LOG = logger<LiveMarkupController>()
        const val DEBOUNCE_MS = 200L
        /** Marks a fold region as ours and says what it hides. */
        val KIND: Key<MarkupKind> = Key.create("agenstorm.liveMarkup.kind")
    }
}
