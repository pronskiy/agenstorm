package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.FoldingGroup
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.FoldingListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.ThreadingAssertions
import com.pronskiy.agenstorm.core.AgenstormBundle
import java.awt.Cursor
import java.awt.event.MouseEvent
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
 * Caret policy (F1.3, per element since F3.1): an inline element (bold, italic, strike, code, link) is revealed while
 * a caret is inside it, touches either end or stands one character away on the same line (a zero-width placeholder
 * has no caret position of its own, so the element must open before the caret reaches it or arrow keys skip its
 * first marker), or a selection overlaps it; a block marker (heading, bullet, checkbox)
 * is revealed while a caret is on its line. Everything else stays collapsed, so the raw Markdown is there to edit and
 * what is selected is what gets copied. The two markers of one element share a `FoldingGroup`, and the element's span
 * is the group's current extent, so it follows edits without extra bookkeeping. The policy is part of every sync and
 * is re-applied, coalesced through `invokeLater`, on every caret or selection change; a keystroke that keeps the caret
 * inside the same element changes nothing and costs no fold operation.
 *
 * Coexistence (F1.4): a `FoldingListener` re-applies the policy after anyone else's batch (Expand All expands our
 * regions, Collapse All collapses the caret line's) and asks for a re-sync when a region of ours is removed by
 * someone else (the folding model's rebuild); our own batches are flagged so they trigger neither.
 *
 * Checkboxes (F2.2): a left press on a ☐ / ☑ placeholder flips the box in one undoable command and swaps the
 * placeholder at once; the event is consumed so the editor neither moves the caret nor expands the region. The
 * pointer becomes a hand over a checkbox.
 *
 * Light regions are created with `FoldingModelEx.createFoldRegion` rather than through a `FoldingBuilder`: builder
 * regions shorter than two characters are dropped, ours are often one character long (SPEC.md decision 10).
 *
 * Persisted folding state: when an editor closes, the platform remembers every collapsed region as a plain range
 * plus placeholder and recreates them on reopen (`DocumentFoldingInfo`), our regions included — as ordinary regions
 * without [KIND] that nothing would ever expand, and which block ours (the fold tree rejects a second region on the
 * same range). The sync therefore replaces any foreign region sitting exactly on a wanted range; a restore that
 * lands on top of our regions only collapses them, and the policy reopens the caret line afterwards.
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
    private var ownBatch = false

    init {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                resync.tryEmit(Unit)
            }
        }, this)
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = scheduleCaretPolicy()

            override fun caretAdded(event: CaretEvent) = scheduleCaretPolicy()

            override fun caretRemoved(event: CaretEvent) = scheduleCaretPolicy()
        }, this)
        editor.selectionModel.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) = scheduleCaretPolicy()
        }, this)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mousePressed(event: EditorMouseEvent) {
                if (event.mouseEvent.button != MouseEvent.BUTTON1 || event.area != EditorMouseEventArea.EDITING_AREA) return
                val region = event.collapsedFoldRegion ?: return
                if (toggleCheckbox(region)) event.consume()
            }
        }, this)
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) {
                val overCheckbox = event.area == EditorMouseEventArea.EDITING_AREA && event.collapsedFoldRegion?.getUserData(KIND)?.isCheckbox == true
                editor.setCustomCursor(this@LiveMarkupController, if (overCheckbox) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null)
            }
        }, this)
        editor.foldingModel.addListener(object : FoldingListener {
            override fun onFoldProcessingEnd() {
                if (!ownBatch) scheduleCaretPolicy()
            }

            override fun beforeFoldRegionRemoved(region: FoldRegion) {
                if (!ownBatch && region.getUserData(KIND) != null) requestSync()
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

    /**
     * Expands our regions whose element (or line, for block markers) holds a caret or overlaps a selection, collapses
     * the rest; one batch, skipped entirely when nothing would change.
     */
    fun applyCaretPolicy() {
        ThreadingAssertions.assertEventDispatchThread()
        if (editor.isDisposed) return
        val ours = regions()
        if (ours.isEmpty()) return
        val spans = groupSpans(ours)
        val carets = Carets.of(editor)
        val changes = ArrayList<Pair<FoldRegion, Boolean>>()
        for (region in ours) {
            val kind = region.getUserData(KIND) ?: continue
            val target = isRevealed(kind, region.startOffset, spans[region.group] ?: region.textRange, carets)
            if (region.isExpanded != target) changes += region to target
        }
        if (changes.isEmpty()) return
        batch {
            for ((region, target) in changes) region.isExpanded = target
        }
    }

    /**
     * Flips the task box behind [region] (`[ ]` ↔ `[x]`) if it is one of our checkbox regions; the placeholder and kind
     * follow at once so the next sync keeps the region. Returns false for any other region.
     */
    fun toggleCheckbox(region: FoldRegion): Boolean {
        ThreadingAssertions.assertEventDispatchThread()
        val kind = region.getUserData(KIND) ?: return false
        val (next, mark) = when (kind) {
            MarkupKind.CHECKBOX_OFF -> MarkupKind.CHECKBOX_ON to "x"
            MarkupKind.CHECKBOX_ON -> MarkupKind.CHECKBOX_OFF to " "
            else -> return false
        }
        val document = editor.document
        if (!region.isValid || region.endOffset - region.startOffset != 3 || !document.isWritable || project.isDisposed) return false
        val middle = region.startOffset + 1
        WriteCommandAction.runWriteCommandAction(project, AgenstormBundle.message("markdown.toggleCheckbox.command"), null, {
            document.replaceString(middle, middle + 1, mark)
        })
        if (region.isValid) {
            batch {
                region.putUserData(KIND, next)
                region.setPlaceholderText(if (next == MarkupKind.CHECKBOX_ON) MarkupRangeCollector.CHECKBOX_ON_PLACEHOLDER else MarkupRangeCollector.CHECKBOX_OFF_PLACEHOLDER)
            }
        }
        return true
    }

    /** Removes every region of ours in one batch; the Markdown plugin's regions stay. */
    fun removeAll() {
        if (editor.isDisposed || !ApplicationManager.getApplication().isDispatchThread) return
        val model = editor.foldingModel
        val ours = regions()
        if (ours.isEmpty()) return
        batch { ours.forEach(model::removeFoldRegion) }
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
        val ranges = MarkupRangeCollector.collect(file, MarkupRangeCollector.Options.fromSettings())
        if (LOG.isDebugEnabled) LOG.debug("live markup: ${ranges.size} ranges collected in ${(System.nanoTime() - started) / 1_000_000} ms")
        return Snapshot(ranges, document.modificationStamp)
    }

    private fun apply(snapshot: Snapshot) {
        ThreadingAssertions.assertEventDispatchThread()
        if (editor.isDisposed || editor.document.modificationStamp != snapshot.stamp) return
        val started = System.nanoTime()
        val wanted = LinkedHashMap<RegionKey, MarkupRange>()
        for (range in snapshot.ranges) wanted[RegionKey(range.range.startOffset, range.range.endOffset, range.kind, range.placeholder)] = range
        val carets = Carets.of(editor)
        val model = editor.foldingModel
        val ours = regions()
        val siblings = ours.groupBy { it.group }
        val stillWanted = HashMap(wanted)
        // A region stays when every marker of its group is still wanted with the same element span; otherwise the
        // element changed shape and the whole group is rebuilt. Element span → surviving group, so a recreated marker
        // joins its sibling's group.
        fun intact(region: FoldRegion, entry: MarkupRange): Boolean {
            val group = region.group ?: return false
            return siblings.getValue(group).all { sibling ->
                val kind = sibling.getUserData(KIND) ?: return@all false
                stillWanted[RegionKey(sibling.startOffset, sibling.endOffset, kind, sibling.placeholderText)]?.span == entry.span
            }
        }
        val groups = HashMap<TextRange, FoldingGroup>()
        var removed = 0
        var created = 0
        var replaced = 0
        batch {
            for (region in ours) {
                val kind = region.getUserData(KIND) ?: continue
                val key = RegionKey(region.startOffset, region.endOffset, kind, region.placeholderText)
                val entry = wanted[key]
                if (entry != null && intact(region, entry)) {
                    wanted.remove(key)
                    groups[entry.span] = region.group!!
                    val expanded = isRevealed(kind, region.startOffset, entry.span, carets)
                    if (region.isExpanded != expanded) region.isExpanded = expanded
                    continue
                }
                model.removeFoldRegion(region)
                removed++
            }
            for (range in wanted.values) {
                val start = range.range.startOffset
                val end = range.range.endOffset
                val orphan = model.getFoldRegion(start, end)
                if (orphan != null && orphan.getUserData(KIND) == null) {
                    model.removeFoldRegion(orphan)
                    replaced++
                }
                val group = groups.getOrPut(range.span) { FoldingGroup.newGroup(GROUP_NAME) }
                val region = model.createFoldRegion(start, end, range.placeholder, group, false) ?: continue
                region.putUserData(KIND, range.kind)
                region.setGutterMarkEnabledForSingleLine(false)
                region.isExpanded = isRevealed(range.kind, start, range.span, carets)
                created++
            }
        }
        if (LOG.isDebugEnabled) LOG.debug("live markup: $created regions created, $removed removed, $replaced foreign ones replaced in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    /** Where a region should be revealed: around its element for inline markup, its whole line for block markers. */
    private fun isRevealed(kind: MarkupKind, start: Int, span: TextRange, carets: Carets): Boolean {
        val reveal = if (kind.isBlock) lineSpan(start) else approach(span, lineSpan(span.startOffset), lineSpan(span.endOffset))
        return isRevealed(reveal, carets)
    }

    private fun lineSpan(offset: Int): TextRange {
        val document = editor.document
        val line = document.getLineNumber(offset.coerceIn(0, document.textLength))
        return TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
    }

    /** The current extent of every group among [ours]: from its first marker's start to its last marker's end. */
    private fun groupSpans(ours: List<FoldRegion>): Map<FoldingGroup, TextRange> {
        val spans = HashMap<FoldingGroup, TextRange>()
        for (region in ours) {
            val group = region.group ?: continue
            val known = spans[group]
            spans[group] = if (known == null) region.textRange else TextRange(minOf(known.startOffset, region.startOffset), maxOf(known.endOffset, region.endOffset))
        }
        return spans
    }

    /** Our batch: carets are never moved, the caret keeps its place on screen, and our own folding listener stays quiet. */
    private fun batch(operation: () -> Unit) {
        ownBatch = true
        try {
            editor.foldingModel.runBatchFoldingOperation(operation, false, true)
        } finally {
            ownBatch = false
        }
    }

    private fun scheduleCaretPolicy() {
        if (policyScheduled || editor.isDisposed) return
        policyScheduled = true
        ApplicationManager.getApplication().invokeLater({
            policyScheduled = false
            applyCaretPolicy()
        }, Condition<Any?> { editor.isDisposed })
    }

    /** Caret offsets and selections at one point in time. */
    class Carets(val offsets: List<Int>, val selections: List<TextRange>) {
        companion object {
            fun of(editor: EditorEx): Carets {
                val all = editor.caretModel.allCarets
                return Carets(all.map { it.offset }, all.filter { it.hasSelection() }.map { TextRange(it.selectionStart, it.selectionEnd) })
            }
        }
    }

    companion object {
        private val LOG = logger<LiveMarkupController>()
        private const val GROUP_NAME = "agenstorm.liveMarkup"
        const val DEBOUNCE_MS = 200L
        /** Marks a fold region as ours and says what it hides. */
        val KIND: Key<MarkupKind> = Key.create("agenstorm.liveMarkup.kind")

        /** A span is revealed while a caret is inside it or touches either end, or a selection overlaps it. */
        fun isRevealed(span: TextRange, carets: Carets): Boolean =
            carets.offsets.any { it >= span.startOffset && it <= span.endOffset } ||
                carets.selections.any { it.startOffset < span.endOffset && it.endOffset > span.startOffset }

        /**
         * The element's span grown by one character on each side, but not past its first or last line: an arrow key
         * pressed next to a collapsed element lands beyond its zero-width placeholder, so the element opens a step early.
         */
        fun approach(span: TextRange, firstLine: TextRange, lastLine: TextRange): TextRange =
            TextRange(maxOf(firstLine.startOffset, span.startOffset - 1), minOf(lastLine.endOffset, span.endOffset + 1))
    }
}
