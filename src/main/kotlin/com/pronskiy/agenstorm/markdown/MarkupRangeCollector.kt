package com.pronskiy.agenstorm.markdown

import com.intellij.lang.ASTNode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiUtilCore
import com.pronskiy.agenstorm.core.AgenstormSettings
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes

/** What a hidden range stands for; the controller keys its fold regions by kind and range. */
enum class MarkupKind {
    STRONG, EMPH, STRIKE, CODE, HEADING, LINK_OPEN, LINK_TAIL, CHECKBOX_OFF, CHECKBOX_ON, BULLET, FENCE_OPEN, FENCE_CLOSE, QUOTE_MARKER;

    val isCheckbox: Boolean get() = this == CHECKBOX_OFF || this == CHECKBOX_ON

    val isFence: Boolean get() = this == FENCE_OPEN || this == FENCE_CLOSE

    /** Block-level markers are revealed for their whole line; inline ones only for their element (Phase F3). */
    val isBlock: Boolean get() = this == HEADING || this == BULLET || this == QUOTE_MARKER || isCheckbox || isFence
}

/**
 * A stretch of Markdown syntax to fold away, what to draw in its place (usually nothing), and the [span] of the
 * element it belongs to: both markers of `**bold**` carry the same span, so the controller can pair them.
 */
data class MarkupRange(val kind: MarkupKind, val range: TextRange, val placeholder: String, val span: TextRange)

/** What a [MarkdownBlock] is; the rest of Phase H3 adds thematic breaks. */
enum class MarkdownBlockKind { CODE_FENCE, BLOCK_QUOTE }

/**
 * A block-level construct the renderer paints behind (Epic H): [span] is the whole element, [language] the
 * fence's info string exactly as written, or null when it has none.
 */
data class MarkdownBlock(val kind: MarkdownBlockKind, val span: TextRange, val language: String?)

/** Everything one walk of the file produced: the ranges to fold and the blocks to paint behind. */
data class Markup(val ranges: List<MarkupRange>, val blocks: List<MarkdownBlock>)

/**
 * Step F1.1. Walks a Markdown PSI tree and lists the marker characters the live-markup mode hides: emphasis and
 * strong markers, strikethrough tildes, the outer backticks of code spans, ATX heading hashes with their space,
 * the brackets and destination of inline links, and task-list checkboxes (replaced by ☐ / ☑) and, when asked, list bullets (replaced by •). A fenced code block
 * (Epic H) loses its ``` lines and yields a [MarkdownBlock], but its body is never touched; indented code
 * blocks, HTML blocks and images are left exactly as written, and so are link destinations and titles (they
 * sit inside the folded link tail anyway).
 *
 * The ranges never overlap: each one covers only marker tokens, and nested constructs (`***both***`, bold inside
 * link text) keep their markers as direct children of their own node. Output is sorted by start offset.
 */
object MarkupRangeCollector {

    private val SKIPPED: TokenSet = TokenSet.create(
        MarkdownElementTypes.CODE_BLOCK,
        MarkdownElementTypes.HTML_BLOCK,
        MarkdownElementTypes.IMAGE,
        MarkdownElementTypes.LINK_DESTINATION,
        MarkdownElementTypes.LINK_TITLE,
    )
    private val HEADINGS: TokenSet = TokenSet.create(
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
    )

    const val CHECKBOX_OFF_PLACEHOLDER = "☐"
    const val CHECKBOX_ON_PLACEHOLDER = "☑"
    const val BULLET_PLACEHOLDER = "•"

    /** A `>` becomes one space, so hiding it — and revealing it again at the caret — never moves a column. */
    const val QUOTE_MARKER_PLACEHOLDER = " "

    /** The optional parts (settings of Phase F2 and H2); [fromSettings] reads the current values. */
    data class Options(
        val checkboxes: Boolean = true,
        val bullets: Boolean = true,
        val codeBlocks: Boolean = true,
        val blockQuotes: Boolean = true,
    ) {
        companion object {
            fun fromSettings(): Options = AgenstormSettings.getInstance().state.let {
                Options(
                    checkboxes = it.liveMarkupCheckboxes,
                    bullets = it.liveMarkupBullets,
                    codeBlocks = it.liveMarkupCodeBlocks,
                    blockQuotes = it.liveMarkupBlockQuotes,
                )
            }
        }
    }

    /** Requires read access. Deterministic: sorted by start offset, disjoint ranges. */
    fun collect(file: PsiFile, options: Options = Options.fromSettings()): List<MarkupRange> =
        collectMarkup(file, options).ranges

    /** Requires read access. As [collect], plus the blocks Epic H paints behind. */
    fun collectMarkup(file: PsiFile, options: Options = Options.fromSettings()): Markup {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val text = file.viewProvider.contents
        val out = ArrayList<MarkupRange>()
        val blocks = ArrayList<MarkdownBlock>()
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                val type = PsiUtilCore.getElementType(element) ?: return
                if (type in SKIPPED) return // not calling super skips the subtree
                if (type == MarkdownElementTypes.CODE_FENCE) {
                    // Never walked into: the body is code, and the injected highlighting must be left alone.
                    if (options.codeBlocks) codeFence(element.node, out, blocks)
                    return
                }
                when (type) {
                    // Outermost only: one card per quote, however deeply the inner ones nest.
                    MarkdownElementTypes.BLOCK_QUOTE -> if (options.blockQuotes && !insideBlockQuote(element)) blockQuote(element, text, out, blocks)
                    MarkdownElementTypes.STRONG -> markers(element.node, MarkdownTokenTypes.EMPH, MarkupKind.STRONG, out)
                    MarkdownElementTypes.EMPH -> markers(element.node, MarkdownTokenTypes.EMPH, MarkupKind.EMPH, out)
                    MarkdownElementTypes.STRIKETHROUGH -> markers(element.node, MarkdownTokenTypes.TILDE, MarkupKind.STRIKE, out)
                    MarkdownElementTypes.CODE_SPAN -> codeSpan(element.node, out)
                    MarkdownElementTypes.INLINE_LINK -> inlineLink(element.node, out)
                    MarkdownTokenTypes.CHECK_BOX -> if (options.checkboxes) checkbox(element.node, out)
                    MarkdownTokenTypes.LIST_BULLET -> if (options.bullets) bullet(element.node, out)
                    in HEADINGS -> heading(element.node, text, out)
                }
                super.visitElement(element)
            }
        })
        out.sortBy { it.range.startOffset }
        blocks.sortBy { it.span.startOffset }
        return Markup(dropSwallowedQuoteMarkers(out), blocks)
    }

    /**
     * Both fence lines lose their ` ``` ` — the opening one its info string too — but keep their EOL, so the
     * card gets a header row for the language chip and a footer row, and neither line loses its number.
     * `CODE_FENCE_CONTENT` is never touched.
     * A fence left unterminated at the end of the file has no closing token and emits only the opener.
     */
    private fun codeFence(node: ASTNode, out: MutableList<MarkupRange>, blocks: MutableList<MarkdownBlock>) {
        val children = node.getChildren(null)
        val open = children.firstOrNull { it.elementType == MarkdownTokenTypes.CODE_FENCE_START } ?: return
        val language = children.firstOrNull { it.elementType == MarkdownTokenTypes.FENCE_LANG }
        val span = node.textRange
        // Through the info string when there is one, so the spaces between the two go as well.
        val openEnd = language?.textRange?.endOffset ?: open.textRange.endOffset
        out += MarkupRange(MarkupKind.FENCE_OPEN, TextRange(open.startOffset, openEnd), "", span)

        val close = children.lastOrNull { it.elementType == MarkdownTokenTypes.CODE_FENCE_END }
        // The closing ``` only, never the line break before it. Taking the break too would make the line
        // vanish, and with it its number, and a fold region covering more than one line always gets a gutter
        // arrow — `setGutterMarkEnabledForSingleLine` can only suppress the single-line ones. The line is left
        // empty instead, which gives the card a footer row to match its header row.
        if (close != null) out += MarkupRange(MarkupKind.FENCE_CLOSE, close.textRange, "", span)
        blocks += MarkdownBlock(MarkdownBlockKind.CODE_FENCE, span, language?.text?.trim()?.takeIf { it.isNotEmpty() })
    }

    /**
     * Drops the quote markers another range already covers, so the output stays disjoint.
     *
     * The markers are found by scanning lines while every other range comes from a token, and the two can
     * meet: a closing ``` inside a quote is one token that starts at the head of its line, `>` included, so
     * the fence range already hides that marker — and starts at the very same offset it does. Everything else
     * is disjoint by construction.
     */
    private fun dropSwallowedQuoteMarkers(sorted: List<MarkupRange>): List<MarkupRange> {
        val covered = sorted.filter { it.kind != MarkupKind.QUOTE_MARKER }
        if (covered.isEmpty() || covered.size == sorted.size) return sorted
        val out = ArrayList<MarkupRange>(sorted.size)
        // Both lists run in start order and the covering ranges are disjoint, so one forward pointer is enough.
        var next = 0
        for (range in sorted) {
            if (range.kind != MarkupKind.QUOTE_MARKER) {
                out += range
                continue
            }
            val start = range.range.startOffset
            while (next < covered.size && covered[next].range.endOffset <= start) next++
            if (next < covered.size && covered[next].range.startOffset <= start) continue
            out += range
        }
        return out
    }

    /**
     * A block quote: one card behind the whole element, and every `>` on its lines folded to a space.
     *
     * The markers are found in the text, not in the tree. The parser puts the first `>` of a quote in a
     * `MarkdownTokenTypes.BLOCK_QUOTE` leaf but leaves the continuation markers wherever the line landed —
     * inside the paragraph for a wrapped line, and as plain `WHITE_SPACE` before a list or a nested quote —
     * so walking the lines is both simpler and complete. [heading] already reads the file text the same way.
     *
     * Called for the outermost quote only, and its scan covers the nested ones' markers too.
     */
    private fun blockQuote(element: PsiElement, text: CharSequence, out: MutableList<MarkupRange>, blocks: MutableList<MarkdownBlock>) {
        val span = element.textRange
        for (offset in quoteMarkerOffsets(text, span.startOffset, span.endOffset)) {
            val range = TextRange(offset, offset + 1)
            out += MarkupRange(MarkupKind.QUOTE_MARKER, range, QUOTE_MARKER_PLACEHOLDER, range)
        }
        blocks += MarkdownBlock(MarkdownBlockKind.BLOCK_QUOTE, span, language = null)
    }

    /**
     * Offsets of every `>` in the quote prefix of each line of `[start, end)`. A prefix is the run of `>`
     * characters at the head of the line, spaces allowed before and between them (`>`, `> `, `>>`, `> > `);
     * the scan of a line stops at its first character that is neither. A lazy continuation line, which carries
     * no `>` at all, contributes nothing.
     */
    internal fun quoteMarkerOffsets(text: CharSequence, start: Int, end: Int): List<Int> {
        val out = ArrayList<Int>()
        var lineStart = start
        while (lineStart < end) {
            var i = lineStart
            while (i < end && text[i] != '\n') {
                if (text[i] == '>') {
                    out += i
                } else if (text[i] != ' ' && text[i] != '\t') {
                    break
                }
                i++
            }
            while (i < end && text[i] != '\n') i++
            lineStart = i + 1
        }
        return out
    }

    /** True when [element] sits inside another block quote, so only the outermost one draws a card. */
    private fun insideBlockQuote(element: PsiElement): Boolean {
        var parent = element.parent
        while (parent != null && parent !is PsiFile) {
            if (PsiUtilCore.getElementType(parent) == MarkdownElementTypes.BLOCK_QUOTE) return true
            parent = parent.parent
        }
        return false
    }

    /** Leading and trailing runs of [marker] tokens among the node's direct children; both must exist and leave content between. */
    private fun markers(node: ASTNode, marker: IElementType, kind: MarkupKind, out: MutableList<MarkupRange>) {
        val children = node.getChildren(null)
        val lead = children.takeWhile { it.elementType == marker }
        val tail = children.takeLastWhile { it.elementType == marker }
        if (lead.isEmpty() || tail.isEmpty() || lead.size + tail.size >= children.size) return
        val span = node.textRange
        out += MarkupRange(kind, TextRange(lead.first().startOffset, lead.last().textRange.endOffset), "", span)
        out += MarkupRange(kind, TextRange(tail.first().startOffset, tail.last().textRange.endOffset), "", span)
    }

    /** Only the outer backticks: a double-backtick span may legitimately contain a single backtick. */
    private fun codeSpan(node: ASTNode, out: MutableList<MarkupRange>) {
        val children = node.getChildren(null)
        val open = children.firstOrNull() ?: return
        val close = children.lastOrNull() ?: return
        if (open === close || open.elementType != MarkdownTokenTypes.BACKTICK || close.elementType != MarkdownTokenTypes.BACKTICK) return
        if (children.size < 3) return
        out += MarkupRange(MarkupKind.CODE, open.textRange, "", node.textRange)
        out += MarkupRange(MarkupKind.CODE, close.textRange, "", node.textRange)
    }

    /**
     * The opening `#`s plus the one space after them; closing `#`s (rare) plus the one space before them. The space is
     * not a token of its own (it belongs to the content node), hence the look at the document text. Empty headings are
     * left alone so the line does not vanish.
     */
    private fun heading(node: ASTNode, text: CharSequence, out: MutableList<MarkupRange>) {
        val children = node.getChildren(null)
        val content = children.firstOrNull { it.elementType == MarkdownTokenTypes.ATX_CONTENT } ?: return
        if (content.text.isBlank()) return
        for (child in children) {
            if (child.elementType != MarkdownTokenTypes.ATX_HEADER) continue
            var start = child.startOffset
            var end = child.textRange.endOffset
            if (child.startOffset < content.startOffset) {
                if (end < text.length && text[end] == ' ') end++
            } else {
                if (start > 0 && text[start - 1] == ' ') start--
            }
            out += MarkupRange(MarkupKind.HEADING, TextRange(start, end), "", node.textRange)
        }
    }

    /** `[` becomes [MarkupKind.LINK_OPEN]; `](destination "title")` becomes one [MarkupKind.LINK_TAIL]. Empty link text stays raw. */
    private fun inlineLink(node: ASTNode, out: MutableList<MarkupRange>) {
        val linkText = node.findChildByType(MarkdownElementTypes.LINK_TEXT) ?: return
        val textChildren = linkText.getChildren(null)
        val open = textChildren.firstOrNull()?.takeIf { it.elementType == MarkdownTokenTypes.LBRACKET } ?: return
        val close = textChildren.lastOrNull()?.takeIf { it.elementType == MarkdownTokenTypes.RBRACKET } ?: return
        if (open === close || close.startOffset <= open.textRange.endOffset) return
        out += MarkupRange(MarkupKind.LINK_OPEN, open.textRange, "", node.textRange)
        out += MarkupRange(MarkupKind.LINK_TAIL, TextRange(close.startOffset, node.textRange.endOffset), "", node.textRange)
    }

    /** The token is `- ` (or `* `, `+ `) with its trailing space; only the marker character becomes a •. */
    private fun bullet(node: ASTNode, out: MutableList<MarkupRange>) {
        if (node.text.firstOrNull() !in BULLET_CHARS) return
        out += MarkupRange(MarkupKind.BULLET, TextRange.from(node.startOffset, 1), BULLET_PLACEHOLDER, node.textRange)
    }

    private val BULLET_CHARS = setOf('-', '*', '+')

    /** The token is `[ ] ` / `[x] ` with a trailing space; only the three bracket characters are replaced. */
    private fun checkbox(node: ASTNode, out: MutableList<MarkupRange>) {
        val (kind, placeholder) = when (node.text.trimEnd()) {
            "[ ]" -> MarkupKind.CHECKBOX_OFF to CHECKBOX_OFF_PLACEHOLDER
            "[x]", "[X]" -> MarkupKind.CHECKBOX_ON to CHECKBOX_ON_PLACEHOLDER
            else -> return
        }
        out += MarkupRange(kind, TextRange.from(node.startOffset, 3), placeholder, node.textRange)
    }
}
