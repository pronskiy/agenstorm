package com.pronskiy.agenstorm.terminal.enhance

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.FoldingListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
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
import java.awt.Cursor
import java.awt.Graphics2D
import java.awt.event.MouseEvent
import java.awt.geom.Rectangle2D

/**
 * Step I2.1 (born as the I1.3 spike, decision 70). One per reworked-terminal output editor: every document
 * change asks for a debounced sync, the sync runs [BlockDetector] over the text appended since the last one off
 * the EDT, and the blocks it finds become light fold regions, collapsed to the rule's summary, on the EDT.
 *
 * Shaped like `LiveMarkupController`, with what the terminal forces: the document is append-only and trimmed
 * from the top (by characters, at the *New terminal output capacity* setting), so the scan position moves with
 * a trim and a region the trim cut through is dropped rather than recreated; and there is no caret policy — the
 * caret is the shell prompt — so a region opens only when the user asks. There is no gutter icon either: the
 * terminal creates its editor with gutter icons and the folding outline switched off, and turning them on would
 * change how the terminal looks for everyone.
 *
 * Every block gets a chevron inlay in front of its first line — ▸ folded, ▾ open — and that is the one thing
 * to click: it toggles the region, and it is also what folds an open block back, since the terminal has no
 * gutter to do it from. The platform's own click on the placeholder opens the region as well. A `tree` or
 * `json` block is coloured in place from the moment it is found (I2.2, decision 72): keys, types, class names,
 * strings and numbers in the editor scheme's language colours, visible whenever the block is open.
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
    /** Told once per rule the detector switched off for blowing its budget; the tests hand in a recorder. */
    onRuleDisabled: (EnhancerRule, String) -> Unit = { rule, why -> RuleFileNotice.reportDisabled(rule, why, RuleRepository.getInstance().folder) },
) : Disposable {

    private val resync = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val detector = BlockDetector(onRuleDisabled = onRuleDisabled)

    /** How far the output has been scanned. Written on the EDT only; read from the scan thread. */
    @Volatile
    private var scannedUpTo = 0

    /** Set by a trim, cleared by the next apply, which drops the regions the trim cut through. */
    private var trimmed = false
    private var ownBatch = false
    private val job: Job?

    /** The colours and the chevron of each region of ours; EDT only. */
    private class Decoration(val highlighters: List<RangeHighlighter>, val chevron: Inlay<*>?)

    private val decorations = HashMap<FoldRegion, Decoration>()

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
                if (region.getUserData(RULE) == null) return
                undecorate(region)
                if (ownBatch || !region.isValid) return
                // Someone else took a region of ours: its text is still there, so look at it again.
                scannedUpTo = minOf(scannedUpTo, region.startOffset)
                resync.tryEmit(Unit)
            }

            override fun onFoldProcessingEnd() {
                for (decoration in decorations.values) decoration.chevron?.update()
            }
        }, this)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mousePressed(event: EditorMouseEvent) {
                if (event.mouseEvent.button != MouseEvent.BUTTON1 || event.area != EditorMouseEventArea.EDITING_AREA) return
                val chevron = event.inlay?.renderer as? Chevron ?: return
                toggle(chevron.region)
                event.consume()
            }
        }, this)
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) {
                val overChevron = event.area == EditorMouseEventArea.EDITING_AREA && event.inlay?.renderer is Chevron
                editor.setCustomCursor(this@TerminalEnhancerController, if (overChevron) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null)
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

    /** The rules changed: drop every region of ours and scan the whole output again. EDT. */
    fun reset() {
        ThreadingAssertions.assertEventDispatchThread()
        if (editor.isDisposed) return
        val ours = regions()
        if (ours.isNotEmpty()) batch { for (region in ours) editor.foldingModel.removeFoldRegion(region) }
        scannedUpTo = 0
        trimmed = false
        requestSync()
    }

    /** Folds an open block of ours, opens a folded one. EDT. What the chevron does. */
    fun toggle(region: FoldRegion) {
        ThreadingAssertions.assertEventDispatchThread()
        if (editor.isDisposed || !region.isValid || region.getUserData(RULE) == null) return
        batch { region.isExpanded = !region.isExpanded }
    }

    /** Every region this controller created and that is still valid. */
    fun regions(): List<FoldRegion> = editor.foldingModel.allFoldRegions.filter { it.isValid && it.getUserData(RULE) != null }

    /** The colour highlighters of [region], for the tests. */
    fun highlightersOf(region: FoldRegion): List<RangeHighlighter> = decorations[region]?.highlighters ?: emptyList()

    /** The chevron in front of [region], for the tests. */
    fun chevronOf(region: FoldRegion): Inlay<*>? = decorations[region]?.chevron

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
                region.putUserData(RENDER, block.render)
                region.isExpanded = false
                decorate(region, block)
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

    /** The colours of a `tree` or `json` block and the chevron of every block. */
    private fun decorate(region: FoldRegion, block: EnhancedBlock) {
        val start = region.startOffset
        val text = block.payload ?: editor.document.getText(region.textRange)
        val markup = editor.markupModel
        val highlighters = BlockColorizer.tokens(text, block.render).mapNotNull { token ->
            val tokenStart = start + token.range.startOffset
            val tokenEnd = start + token.range.endOffset
            if (tokenEnd > editor.document.textLength) return@mapNotNull null
            markup.addRangeHighlighter(token.kind.key, tokenStart, tokenEnd, HighlighterLayer.ADDITIONAL_SYNTAX, HighlighterTargetArea.EXACT_RANGE)
        }
        val chevron = editor.inlayModel.addInlineElement(start, false, Chevron(region))
        decorations[region] = Decoration(highlighters, chevron)
    }

    private fun undecorate(region: FoldRegion) {
        val decoration = decorations.remove(region) ?: return
        if (editor.isDisposed) return
        for (highlighter in decoration.highlighters) editor.markupModel.removeHighlighter(highlighter)
        decoration.chevron?.let { Disposer.dispose(it) }
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
        if (ours.isNotEmpty()) batch { for (region in ours) editor.foldingModel.removeFoldRegion(region) }
        for (region in decorations.keys.toList()) undecorate(region)
    }

    /**
     * ▸ in front of a folded block, ▾ in front of an open one, in the folded-text colour: the one click target,
     * and the way back to folded.
     */
    class Chevron(val region: FoldRegion) : EditorCustomElementRenderer {
        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val editor = inlay.editor
            return editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN)).stringWidth("$OPEN ")
        }

        override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) {
            val editor: Editor = inlay.editor
            g.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
            g.color = editor.colorsScheme.getAttributes(EditorColors.FOLDED_TEXT_ATTRIBUTES)?.foregroundColor ?: editor.colorsScheme.defaultForeground
            val glyph = if (region.isValid && !region.isExpanded) FOLDED else OPEN
            g.drawString(glyph, targetRegion.x.toFloat(), (targetRegion.y + editor.ascent).toFloat())
        }

        companion object {
            const val FOLDED = "▸"
            const val OPEN = "▾"
        }
    }

    companion object {
        private val LOG = logger<TerminalEnhancerController>()
        const val DEBOUNCE_MS = 150L

        /** Marks a fold region as ours, with the id of the rule that made it. */
        val RULE: Key<String> = Key.create("agenstorm.terminal.enhancer.rule")

        /** How the block behind a region of ours is shown: whether it is coloured. */
        val RENDER: Key<RenderMode> = Key.create("agenstorm.terminal.enhancer.render")
    }
}
