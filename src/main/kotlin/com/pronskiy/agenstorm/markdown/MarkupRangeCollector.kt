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
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes

/** What a hidden range stands for; the controller keys its fold regions by kind and range. */
enum class MarkupKind {
    STRONG, EMPH, STRIKE, CODE, HEADING, LINK_OPEN, LINK_TAIL, CHECKBOX_OFF, CHECKBOX_ON;

    val isCheckbox: Boolean get() = this == CHECKBOX_OFF || this == CHECKBOX_ON
}

/** A stretch of Markdown syntax to fold away, and what to draw in its place (usually nothing). */
data class MarkupRange(val kind: MarkupKind, val range: TextRange, val placeholder: String)

/**
 * Step F1.1. Walks a Markdown PSI tree and lists the marker characters the live-markup mode hides: emphasis and
 * strong markers, strikethrough tildes, the outer backticks of code spans, ATX heading hashes with their space,
 * the brackets and destination of inline links, and task-list checkboxes (replaced by ☐ / ☑). Code fences, indented
 * code blocks, HTML blocks and images are left exactly as written, and so are link destinations and titles (they
 * sit inside the folded link tail anyway).
 *
 * The ranges never overlap: each one covers only marker tokens, and nested constructs (`***both***`, bold inside
 * link text) keep their markers as direct children of their own node. Output is sorted by start offset.
 */
object MarkupRangeCollector {

    private val SKIPPED: TokenSet = TokenSet.create(
        MarkdownElementTypes.CODE_FENCE,
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

    /** Requires read access. Deterministic: sorted by start offset, disjoint ranges. */
    fun collect(file: PsiFile): List<MarkupRange> {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val text = file.viewProvider.contents
        val out = ArrayList<MarkupRange>()
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                val type = PsiUtilCore.getElementType(element) ?: return
                if (type in SKIPPED) return // not calling super skips the subtree
                when (type) {
                    MarkdownElementTypes.STRONG -> markers(element.node, MarkdownTokenTypes.EMPH, MarkupKind.STRONG, out)
                    MarkdownElementTypes.EMPH -> markers(element.node, MarkdownTokenTypes.EMPH, MarkupKind.EMPH, out)
                    MarkdownElementTypes.STRIKETHROUGH -> markers(element.node, MarkdownTokenTypes.TILDE, MarkupKind.STRIKE, out)
                    MarkdownElementTypes.CODE_SPAN -> codeSpan(element.node, out)
                    MarkdownElementTypes.INLINE_LINK -> inlineLink(element.node, out)
                    MarkdownTokenTypes.CHECK_BOX -> checkbox(element.node, out)
                    in HEADINGS -> heading(element.node, text, out)
                }
                super.visitElement(element)
            }
        })
        out.sortBy { it.range.startOffset }
        return out
    }

    /** Leading and trailing runs of [marker] tokens among the node's direct children; both must exist and leave content between. */
    private fun markers(node: ASTNode, marker: IElementType, kind: MarkupKind, out: MutableList<MarkupRange>) {
        val children = node.getChildren(null)
        val lead = children.takeWhile { it.elementType == marker }
        val tail = children.takeLastWhile { it.elementType == marker }
        if (lead.isEmpty() || tail.isEmpty() || lead.size + tail.size >= children.size) return
        out += MarkupRange(kind, TextRange(lead.first().startOffset, lead.last().textRange.endOffset), "")
        out += MarkupRange(kind, TextRange(tail.first().startOffset, tail.last().textRange.endOffset), "")
    }

    /** Only the outer backticks: a double-backtick span may legitimately contain a single backtick. */
    private fun codeSpan(node: ASTNode, out: MutableList<MarkupRange>) {
        val children = node.getChildren(null)
        val open = children.firstOrNull() ?: return
        val close = children.lastOrNull() ?: return
        if (open === close || open.elementType != MarkdownTokenTypes.BACKTICK || close.elementType != MarkdownTokenTypes.BACKTICK) return
        if (children.size < 3) return
        out += MarkupRange(MarkupKind.CODE, open.textRange, "")
        out += MarkupRange(MarkupKind.CODE, close.textRange, "")
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
            out += MarkupRange(MarkupKind.HEADING, TextRange(start, end), "")
        }
    }

    /** `[` becomes [MarkupKind.LINK_OPEN]; `](destination "title")` becomes one [MarkupKind.LINK_TAIL]. Empty link text stays raw. */
    private fun inlineLink(node: ASTNode, out: MutableList<MarkupRange>) {
        val linkText = node.findChildByType(MarkdownElementTypes.LINK_TEXT) ?: return
        val textChildren = linkText.getChildren(null)
        val open = textChildren.firstOrNull()?.takeIf { it.elementType == MarkdownTokenTypes.LBRACKET } ?: return
        val close = textChildren.lastOrNull()?.takeIf { it.elementType == MarkdownTokenTypes.RBRACKET } ?: return
        if (open === close || close.startOffset <= open.textRange.endOffset) return
        out += MarkupRange(MarkupKind.LINK_OPEN, open.textRange, "")
        out += MarkupRange(MarkupKind.LINK_TAIL, TextRange(close.startOffset, node.textRange.endOffset), "")
    }

    /** The token is `[ ] ` / `[x] ` with a trailing space; only the three bracket characters are replaced. */
    private fun checkbox(node: ASTNode, out: MutableList<MarkupRange>) {
        val (kind, placeholder) = when (node.text.trimEnd()) {
            "[ ]" -> MarkupKind.CHECKBOX_OFF to CHECKBOX_OFF_PLACEHOLDER
            "[x]", "[X]" -> MarkupKind.CHECKBOX_ON to CHECKBOX_ON_PLACEHOLDER
            else -> return
        }
        out += MarkupRange(kind, TextRange.from(node.startOffset, 3), placeholder)
    }
}
