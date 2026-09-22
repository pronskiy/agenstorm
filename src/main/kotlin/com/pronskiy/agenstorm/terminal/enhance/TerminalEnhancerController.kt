package com.pronskiy.agenstorm.terminal.enhance

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.FoldingListener
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
 * Step I2.1 (born as the I1.3 spike, decision 70). One per reworked-terminal output editor: every document
 * change asks for a debounced sync, the sync runs [BlockDetector] over the text appended since the last one off
 * the EDT, and the blocks it finds become light fold regions, collapsed to the rule's summary, on the EDT.
 *
 * Shaped like `LiveMarkupController`, with what the terminal forces: the document is append-only and trimmed
 * from the top (by characters, at the *New terminal output capacity* setting), so the scan position moves with
 * a trim and a region the trim cut through is dropped rather than recreated; and there is no caret policy — the
 * caret is the shell prompt — so a region expands only when the user clicks its placeholder. There is no gutter
 * icon either: the terminal creates its editor with gutter icons and the folding outline switched off, and
 * turning them on would change how the terminal looks for everyone.
 *
 * A region of ours that something else removes (a `clearFoldRegions` by another plugin) is rescanned from its
 * start on the next sync, the way `LiveMarkupController` re-applies after a foreign folding change.
 */
@OptIn(FlowPreview::class)
class TerminalEnhancerController(
    private val editor: EditorEx,
    private val rules: () -> List<EnhancerRule>,
    scope: CoroutineScope,
    debounceMs: Long = DEBOUNCE_MS,
    /** Off in tests, which drive [syncNow] themselves. */
    backgroundSync: Boolean = true,
) : Disposable {

    private val resync = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val detector = BlockDetector(onRuleDisabled = { rule, why -> LOG.warn("enhancer: rule ${rule.id} (${rule.source}) disabled: $why") })

    /** How far the output has been scanned. Written on the EDT only; read from the scan thread. */
    @Volatile
    private var scannedUpTo = 0

    /** Set by a trim, cleared by the next apply, which drops the regions the trim cut through. */
    private var trimmed = false
    private var ownBatch = false
    private val job: Job?

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
        editor.foldingModel.addListener(object : FoldingListener {
            override fun beforeFoldRegionRemoved(region: FoldRegion) {
                if (ownBatch || region.getUserData(RULE) == null || !region.isValid) return
                // Someone else took a region of ours: its text is still there, so look at it again.
                scannedUpTo = minOf(scannedUpTo, region.startOffset)
                resync.tryEmit(Unit)
            }
        }, this)
        job = if (backgroundSync) {
            scope.launch(CoroutineName("Agenstorm terminal enhancer")) {
                sync()
                resync.debounce(debounceMs).collectLatest { sync() }
            }
        } else {
            null
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

    /** Synchronous variant for callers on the EDT (tests, the settings toggle): scan and apply at once. */
    fun syncNow() {
        ThreadingAssertions.assertEventDispatchThread()
        if (editor.isDisposed) return
        val from = scannedUpTo
        val document = editor.document
        apply(detector.detect(document.immutableCharSequence, rules(), from), from, document.modificationStamp)
    }

    /** Every region this controller created and that is still valid. */
    fun regions(): List<FoldRegion> = editor.foldingModel.allFoldRegions.filter { it.isValid && it.getUserData(RULE) != null }

    /** Where the next scan starts; for the tests of the trim arithmetic. */
    val scanPosition: Int get() = scannedUpTo

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
        batch {
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
        }
        scannedUpTo = detection.resumeFrom
        if (LOG.isDebugEnabled) {
            LOG.debug(
                "enhancer: scanned $from..${editor.document.textLength}, ${detection.blocks.size} blocks, " +
                    "$created regions created, $dropped dropped by a trim, next scan from ${detection.resumeFrom}",
            )
        }
    }

    /** Our batch: the caret is never moved, and our own folding listener stays quiet. */
    private fun batch(operation: () -> Unit) {
        ownBatch = true
        try {
            editor.foldingModel.runBatchFoldingOperation(operation, false, true)
        } finally {
            ownBatch = false
        }
    }

    override fun dispose() {
        job?.cancel()
        if (editor.isDisposed) return
        val ours = regions()
        if (ours.isEmpty()) return
        batch { for (region in ours) editor.foldingModel.removeFoldRegion(region) }
    }

    companion object {
        private val LOG = logger<TerminalEnhancerController>()
        const val DEBOUNCE_MS = 150L

        /** Marks a fold region as ours, with the id of the rule that made it. */
        val RULE: Key<String> = Key.create("agenstorm.terminal.enhancer.rule")
    }
}
