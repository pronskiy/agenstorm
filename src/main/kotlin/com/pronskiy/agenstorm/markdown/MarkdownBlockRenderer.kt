package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.concurrency.ThreadingAssertions
import org.intellij.plugins.markdown.highlighting.MarkdownHighlighterColors
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle

/**
 * Steps H1.2, H3.2 and H3.3. Paints what the blocks [MarkupRangeCollector] found need — the card behind a
 * fenced code block, the card plus accent bar behind a block quote, and the rule across a thematic break —
 * and keeps them in step with the document, exactly as [LiveMarkupController] keeps its fold regions: one
 * [sync] per collector run, driven from the same snapshot.
 *
 * `LINES_IN_RANGE`, not `EXACT_RANGE`: `IterationState` skips exact-range highlighters when it works out what
 * to paint past the end of a line, so only a lines-in-range one reaches the right edge of the viewport and the
 * card does not stop under the text.
 *
 * The colour comes from the scheme's `CODE_FENCE` (or `BLOCK_QUOTE`) background when it defines one. Many
 * schemes do not, so the fallback mixes the editor's own background with its foreground: that darkens on a
 * light theme and lightens on a dark one without hard-coding either. The colour in use is remembered per
 * highlighter, so the next sync after a theme change replaces them rather than leaving the old card behind.
 *
 * A quote's bar is drawn by a [CustomHighlighterRenderer] on that same highlighter, down the column its `>`
 * markers vacated — the collector folds each one to a space, so the column is there and the text never moves.
 * A thematic break is the one kind with no card at all: its highlighter carries no attributes and exists only
 * to hang the rule on. Both are drawn only while the markers they stand in for are folded away.
 */
class MarkdownBlockRenderer(private val editor: EditorEx) : Disposable {

    private class Painted(val highlighter: RangeHighlighter, val kind: MarkdownBlockKind, val background: Color?)

    private data class Key(val start: Int, val end: Int, val kind: MarkdownBlockKind)

    private val painted = ArrayList<Painted>()

    /** EDT. Keeps the highlighters that still cover a wanted block, drops the rest, creates what is missing. */
    fun sync(blocks: List<MarkdownBlock>) {
        ThreadingAssertions.assertEventDispatchThread()
        if (editor.isDisposed) return
        val wanted = LinkedHashMap<Key, MarkdownBlock>()
        for (block in blocks) wanted[Key(block.span.startOffset, block.span.endOffset, block.kind)] = block

        val model = editor.markupModel
        val kept = HashSet<Key>()
        val iterator = painted.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            // A highlighter is a range marker and follows edits, so it is matched on where it is now.
            val here = Key(entry.highlighter.startOffset, entry.highlighter.endOffset, entry.kind)
            if (entry.highlighter.isValid && entry.background == background(entry.kind) && here in wanted && kept.add(here)) continue
            model.removeHighlighter(entry.highlighter)
            iterator.remove()
        }
        for ((key, block) in wanted) {
            if (key in kept) continue
            val background = background(block.kind)
            val highlighter = model.addRangeHighlighter(
                key.start,
                key.end,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                background?.let { color -> TextAttributes().also { it.backgroundColor = color } },
                HighlighterTargetArea.LINES_IN_RANGE,
            )
            highlighter.customRenderer = when (block.kind) {
                MarkdownBlockKind.BLOCK_QUOTE -> QuoteBarRenderer(barColor(editor.colorsScheme, background ?: editor.colorsScheme.defaultBackground))
                MarkdownBlockKind.THEMATIC_BREAK -> RuleLineRenderer(ruleColor(editor.colorsScheme))
                MarkdownBlockKind.CODE_FENCE -> null
            }
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

    private fun background(kind: MarkdownBlockKind): Color? = background(editor.colorsScheme, kind)

    /** The bar down the left of a quote's card: whatever paints its `>` markers, else the card taken further. */
    private fun barColor(scheme: EditorColorsScheme, background: Color): Color {
        scheme.getAttributes(MarkdownHighlighterColors.BLOCK_QUOTE_MARKER)?.foregroundColor?.let { return it }
        return ColorUtil.mix(background, scheme.defaultForeground, BAR_MIX)
    }

    /** The rule itself: whatever paints a `---`, else the editor's own background taken toward the foreground. */
    private fun ruleColor(scheme: EditorColorsScheme): Color {
        scheme.getAttributes(MarkdownHighlighterColors.HRULE)?.foregroundColor?.let { return it }
        return ColorUtil.mix(scheme.defaultBackground, scheme.defaultForeground, RULE_MIX)
    }

    /**
     * Fills the bar and nothing else; the card behind it is the highlighter's own background.
     *
     * It is drawn only while the quote's markers are hidden. Once a caret reaches the block they all come
     * back — they share one [com.intellij.openapi.editor.FoldingGroup] — and the raw `>` down the left is the
     * cue, so a second one beside it would only be in the way. Folding never changes the document, so the
     * first `>` is always there to look up; whether it is collapsed is the whole question.
     */
    private class QuoteBarRenderer(private val color: Color) : CustomHighlighterRenderer {

        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            if (!highlighter.isValid) return
            val document = editor.document
            if (highlighter.endOffset > document.textLength) return
            val marker = firstQuoteMarker(document.immutableCharSequence, highlighter.startOffset)
            if (marker < 0 || editor.foldingModel.getCollapsedRegionAtOffset(marker) == null) return
            val top = editor.logicalPositionToXY(LogicalPosition(document.getLineNumber(highlighter.startOffset), 0)).y
            val bottom = editor.logicalPositionToXY(LogicalPosition(document.getLineNumber(highlighter.endOffset), 0)).y
            val bounds = barBounds(top, bottom, editor.lineHeight, editor.contentComponent.insets.left)
            g.color = color
            g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height)
        }

        override fun equals(other: Any?): Boolean = other is QuoteBarRenderer && other.color == color

        override fun hashCode(): Int = color.hashCode()
    }

    /**
     * Draws the line a folded `---` stands for, across the width in view rather than the width of the widest
     * line in the file, so it reaches the edge of the viewport the way the cards do. Nothing is drawn while
     * the break shows its own characters.
     */
    private class RuleLineRenderer(private val color: Color) : CustomHighlighterRenderer {

        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            if (!highlighter.isValid) return
            val document = editor.document
            if (highlighter.startOffset >= document.textLength) return
            if (editor.foldingModel.getCollapsedRegionAtOffset(highlighter.startOffset) == null) return
            val top = editor.logicalPositionToXY(LogicalPosition(document.getLineNumber(highlighter.startOffset), 0)).y
            val area = editor.scrollingModel.visibleArea
            val bounds = ruleBounds(top, editor.lineHeight, area.x, area.width)
            g.color = color
            g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height)
        }

        override fun equals(other: Any?): Boolean = other is RuleLineRenderer && other.color == color

        override fun hashCode(): Int = color.hashCode()
    }

    companion object {
        /** Enough to read as a card against the editor background, little enough to keep code legible on it. */
        private const val FALLBACK_MIX = 0.06

        /** The bar has to carry against the card it sits on, so it goes much further toward the foreground. */
        private const val BAR_MIX = 0.45

        /** A rule is a divider, not a highlight: enough to see, not enough to cut the page in two. */
        private const val RULE_MIX = 0.30

        /** Null for a thematic break: it is a line drawn on the row, not a card behind one. */
        fun background(scheme: EditorColorsScheme, kind: MarkdownBlockKind): Color? {
            val key = when (kind) {
                MarkdownBlockKind.THEMATIC_BREAK -> return null
                MarkdownBlockKind.BLOCK_QUOTE -> MarkdownHighlighterColors.BLOCK_QUOTE
                MarkdownBlockKind.CODE_FENCE -> MarkdownHighlighterColors.CODE_FENCE
            }
            scheme.getAttributes(key)?.backgroundColor?.let { return it }
            return ColorUtil.mix(scheme.defaultBackground, scheme.defaultForeground, FALLBACK_MIX)
        }

        /**
         * The bar's rectangle: from the top of the block's first line to the bottom of its last, in the column
         * the `>` markers left behind. [bottomY] is the top of the last line, so one [lineHeight] is added.
         */
        fun barBounds(topY: Int, bottomY: Int, lineHeight: Int, x: Int): Rectangle =
            Rectangle(x, topY, JBUIScale.scale(BAR_WIDTH), (bottomY - topY + lineHeight).coerceAtLeast(lineHeight))

        const val BAR_WIDTH = 2

        const val RULE_THICKNESS = 1

        /** The rule's rectangle: [RULE_THICKNESS] scaled, centred in the row that starts at [topY]. */
        fun ruleBounds(topY: Int, lineHeight: Int, x: Int, width: Int): Rectangle {
            val thickness = JBUIScale.scale(RULE_THICKNESS)
            return Rectangle(x, topY + (lineHeight - thickness) / 2, width, thickness)
        }

        /**
         * The offset of the `>` that opens the quote prefix at [from], or -1 when there is none. Only the
         * prefix is read — spaces and tabs may come first, anything else ends the search.
         */
        fun firstQuoteMarker(text: CharSequence, from: Int): Int {
            var i = from
            while (i < text.length) {
                when (text[i]) {
                    '>' -> return i
                    ' ', '\t' -> i++
                    else -> return -1
                }
            }
            return -1
        }
    }
}
