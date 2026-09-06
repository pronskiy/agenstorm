package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import com.intellij.util.concurrency.ThreadingAssertions
import org.intellij.plugins.markdown.highlighting.MarkdownHighlighterColors
import java.awt.Color

/**
 * Step H1.2. Paints the background of the blocks [MarkupRangeCollector] found — for now the card behind a
 * fenced code block — and keeps them in step with the document, exactly as [LiveMarkupController] keeps its
 * fold regions: one [sync] per collector run, driven from the same snapshot.
 *
 * `LINES_IN_RANGE`, not `EXACT_RANGE`: `IterationState` skips exact-range highlighters when it works out what
 * to paint past the end of a line, so only a lines-in-range one reaches the right edge of the viewport and the
 * card does not stop under the text.
 *
 * The colour comes from the scheme's `CODE_FENCE` background when it defines one. Many schemes do not, so the
 * fallback mixes the editor's own background with its foreground: that darkens on a light theme and lightens
 * on a dark one without hard-coding either. The colour in use is remembered per highlighter, so the next sync
 * after a theme change replaces them rather than leaving the old card behind.
 */
class MarkdownBlockRenderer(private val editor: EditorEx) : Disposable {

    private class Painted(val highlighter: RangeHighlighter, val kind: MarkdownBlockKind, val background: Color)

    private data class Key(val start: Int, val end: Int, val kind: MarkdownBlockKind)

    private val painted = ArrayList<Painted>()

    /** EDT. Keeps the highlighters that still cover a wanted block, drops the rest, creates what is missing. */
    fun sync(blocks: List<MarkdownBlock>) {
        ThreadingAssertions.assertEventDispatchThread()
        if (editor.isDisposed) return
        val background = background(editor.colorsScheme)
        val wanted = LinkedHashMap<Key, MarkdownBlock>()
        for (block in blocks) wanted[Key(block.span.startOffset, block.span.endOffset, block.kind)] = block

        val model = editor.markupModel
        val kept = HashSet<Key>()
        val iterator = painted.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            // A highlighter is a range marker and follows edits, so it is matched on where it is now.
            val here = Key(entry.highlighter.startOffset, entry.highlighter.endOffset, entry.kind)
            if (entry.highlighter.isValid && entry.background == background && here in wanted && kept.add(here)) continue
            model.removeHighlighter(entry.highlighter)
            iterator.remove()
        }
        for ((key, block) in wanted) {
            if (key in kept) continue
            val highlighter = model.addRangeHighlighter(
                key.start,
                key.end,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                TextAttributes().also { it.backgroundColor = background },
                HighlighterTargetArea.LINES_IN_RANGE,
            )
            painted += Painted(highlighter, block.kind, background)
        }
    }

    /** Every highlighter this renderer owns and that is still valid. */
    fun highlighters(): List<RangeHighlighter> = painted.map { it.highlighter }.filter { it.isValid }

    fun removeAll() {
        if (!editor.isDisposed) painted.forEach { editor.markupModel.removeHighlighter(it.highlighter) }
        painted.clear()
    }

    override fun dispose() = removeAll()

    private fun background(scheme: EditorColorsScheme): Color {
        scheme.getAttributes(MarkdownHighlighterColors.CODE_FENCE)?.backgroundColor?.let { return it }
        return ColorUtil.mix(scheme.defaultBackground, scheme.defaultForeground, FALLBACK_MIX)
    }

    private companion object {
        /** Enough to read as a card against the editor background, little enough to keep code legible on it. */
        const val FALLBACK_MIX = 0.06
    }
}
