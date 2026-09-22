package com.pronskiy.agenstorm.terminal.enhance

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Key
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
 * Step I1.3 (the spike) growing into I2.1. One per reworked-terminal output editor: every document change asks
 * for a debounced sync, the sync runs [BlockDetector] over the text appended since the last one off the EDT,
 * and the blocks it finds become light fold regions, collapsed to the rule's summary, on the EDT.
 *
 * Shaped like `LiveMarkupController`, with what the terminal forces: the document is append-only and trimmed
 * from the top, so the scan position moves with a trim and regions the trim cut through are dropped rather
 * than recreated; and there is no caret policy — the caret is the shell prompt — so a region expands only when
 * the user clicks it.
 */
@OptIn(FlowPreview::class)
class TerminalEnhancerController(
    private val editor: EditorEx,
    private val rules: () -> List<EnhancerRule>,
    scope: CoroutineScope,
    debounceMs: Long = DEBOUNCE_MS,
) : Disposable {

    private val resync = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val detector = BlockDetector(onRuleDisabled = { rule, why -> LOG.warn("enhancer: rule ${rule.id} (${rule.source}) disabled: $why") })

    /** How far the output has been scanned. Written on the EDT only; read from the scan thread. */
    @Volatile
    private var scannedUpTo = 0

    /** Set by a trim, cleared by the next apply, which drops the regions the trim cut through. */
    private var trimmed = false
    private val job: Job

    init {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (event.offset == 0 && event.newLength == 0 && event.oldLength > 0) {
                    // Scrollback trimming: a pure deletion at the top. What was scanned moves up with it.
                    scannedUpTo = (scannedUpTo - event.oldLength).coerceAtLeast(0)
                    trimmed = true
                } else if (event.offset < scannedUpTo) {
                    // The terminal rewrote something already scanned (a progress bar, a cursor move): scan it again.
                    scannedUpTo = event.offset
                }
                resync.tryEmit(Unit)
            }
        }, this)
        job = scope.launch(CoroutineName("Agenstorm terminal enhancer")) {
            sync()
            resync.debounce(debounceMs).collectLatest { sync() }
        }
    }

    /** Asks for a sync after the debounce period, as a document change would. */
    fun requestSync() {
        resync.tryEmit(Unit)
    }

    /** Scans off the EDT, applies on it; a text that moved on in between is scanned again. */
    suspend fun sync() {
        if (editor.isDisposed) return
        val from = scannedUpTo
        val (text, stamp) = readAction { editor.document.immutableCharSequence to editor.document.modificationStamp }
        val detection = detector.detect(text, rules(), from)
        withContext(Dispatchers.EDT) { apply(detection, from, stamp) }
    }

    /** Every region this controller created and that is still valid. */
    fun regions(): List<FoldRegion> = editor.foldingModel.allFoldRegions.filter { it.isValid && it.getUserData(RULE) != null }

    private fun apply(detection: Detection, from: Int, stamp: Long) {
        ThreadingAssertions.assertEventDispatchThread()
        if (editor.isDisposed) return
        if (editor.document.modificationStamp != stamp) {
            requestSync()
            return
        }
        val model = editor.foldingModel
        var created = 0
        var dropped = 0
        model.runBatchFoldingOperation({
            if (trimmed) {
                trimmed = false
                // A region the trim cut through keeps its tail and now starts at the top; it says nothing true any more.
                for (region in regions()) {
                    if (region.startOffset == 0) {
                        model.removeFoldRegion(region)
                        dropped++
                    }
                }
            }
            for (block in detection.blocks) {
                val start = block.range.startOffset
                val end = block.range.endOffset
                if (end <= start || model.getFoldRegion(start, end) != null) continue
                val region = model.createFoldRegion(start, end, block.summary, null, false) ?: continue
                region.putUserData(RULE, block.ruleId)
                region.isExpanded = false
                created++
            }
        }, false, true)
        scannedUpTo = detection.resumeFrom
        if (LOG.isDebugEnabled) {
            LOG.debug(
                "enhancer: scanned $from..${editor.document.textLength}, ${detection.blocks.size} blocks, " +
                    "$created regions created, $dropped dropped by a trim, next scan from ${detection.resumeFrom}",
            )
        }
    }

    override fun dispose() {
        job.cancel()
        if (editor.isDisposed) return
        val ours = regions()
        if (ours.isEmpty()) return
        editor.foldingModel.runBatchFoldingOperation({ for (region in ours) editor.foldingModel.removeFoldRegion(region) }, false, true)
    }

    companion object {
        private val LOG = logger<TerminalEnhancerController>()
        const val DEBOUNCE_MS = 150L

        /** Marks a fold region as ours, with the id of the rule that made it. */
        val RULE: Key<String> = Key.create("agenstorm.terminal.enhancer.rule")
    }
}
