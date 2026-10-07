package com.pronskiy.agenstorm.markdown.toc

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader

/**
 * Step W1.1. The headings of a Markdown file in document order; call inside a read action. The text is walked here
 * rather than taken from `MarkdownHeader.buildVisibleText`, which keeps emphasis markers and backticks — a reader sees
 * `Install the CLI`, not `Install the **CLI**`. A front matter block is skipped by offset, because whether the Markdown
 * plugin parses it as one depends on a setting, and without it `title: x` over `---` is a setext heading.
 */
object TocCollector {

    private val CONTENT = TokenSet.create(MarkdownTokenTypes.ATX_CONTENT, MarkdownTokenTypes.SETEXT_CONTENT)
    private val LINKS = TokenSet.create(
        MarkdownElementTypes.INLINE_LINK,
        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK,
    )
    private val WHITESPACE = Regex("\\s+")

    fun collect(file: PsiFile): List<TocEntry> {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return emptyList()
        val skipUntil = frontMatterEnd(document.charsSequence)
        return PsiTreeUtil.findChildrenOfType(file, MarkdownHeader::class.java).mapNotNull { header ->
            val offset = header.textRange.startOffset
            if (offset < skipUntil) return@mapNotNull null
            val content = header.node.getChildren(CONTENT).firstOrNull() ?: return@mapNotNull null
            val title = visibleText(content)
            if (title.isEmpty()) null else TocEntry(header.level, title, document.getLineNumber(offset), offset)
        }
    }

    /** The offset just past a front matter block — `---` on the first line, closed by `---` or `...` — or 0. */
    internal fun frontMatterEnd(text: CharSequence): Int {
        val firstEnd = text.indexOf('\n')
        if (firstEnd < 0 || text.subSequence(0, firstEnd).toString().trimEnd('\r') != "---") return 0
        var lineStart = firstEnd + 1
        while (lineStart < text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val line = text.subSequence(lineStart, lineEnd).toString().trimEnd('\r')
            if (line == "---" || line == "...") return lineStart + line.length
            lineStart = lineEnd + 1
        }
        return 0
    }

    /** The heading's text without emphasis, strike, code or autolink delimiters, link destinations, images and inline HTML; spaces collapsed. */
    private fun visibleText(content: ASTNode): String =
        buildString { walk(content, this) }.replace(WHITESPACE, " ").trim()

    private fun walk(node: ASTNode, out: StringBuilder) {
        val type = node.elementType
        when {
            type == MarkdownElementTypes.IMAGE || type == MarkdownTokenTypes.HTML_TAG -> Unit
            type in LINKS -> {
                val text = node.findChildByType(MarkdownElementTypes.LINK_TEXT)
                    ?: node.findChildByType(MarkdownElementTypes.LINK_LABEL)
                    ?: return
                walk(text, out)
            }
            node.firstChildNode == null -> if (!isDelimiter(node)) out.append(node.text)
            else -> {
                var child = node.firstChildNode
                while (child != null) {
                    walk(child, out)
                    child = child.treeNext
                }
            }
        }
    }

    private fun isDelimiter(leaf: ASTNode): Boolean {
        val parent = leaf.treeParent?.elementType
        return when (leaf.elementType) {
            MarkdownTokenTypes.EMPH -> parent == MarkdownElementTypes.EMPH || parent == MarkdownElementTypes.STRONG
            MarkdownTokenTypes.TILDE -> parent == MarkdownElementTypes.STRIKETHROUGH
            MarkdownTokenTypes.BACKTICK -> parent == MarkdownElementTypes.CODE_SPAN
            MarkdownTokenTypes.LBRACKET, MarkdownTokenTypes.RBRACKET ->
                parent == MarkdownElementTypes.LINK_TEXT || parent == MarkdownElementTypes.LINK_LABEL
            MarkdownTokenTypes.LT, MarkdownTokenTypes.GT -> parent == MarkdownElementTypes.AUTOLINK
            else -> false
        }
    }
}
