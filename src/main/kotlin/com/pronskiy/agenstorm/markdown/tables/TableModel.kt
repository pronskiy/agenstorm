package com.pronskiy.agenstorm.markdown.tables

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiUtilCore
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownTokenTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownTable
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownTableRow
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownTableSeparatorRow

/** How a column's cells are aligned, from the separator row (`:---`, `:---:`, `---:`); no colons means left. */
enum class ColumnAlignment { LEFT, CENTER, RIGHT }

/**
 * One stretch of a cell's text drawn in one style. [link] is the destination of an inline link or an autolink;
 * [breakBefore] marks the run that follows a `<br>`.
 */
data class StyledRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
    val breakBefore: Boolean = false,
)

/** A cell: [range] is its content with the surrounding whitespace trimmed (empty for an empty or missing cell). */
data class TableCell(val range: TextRange, val runs: List<StyledRun>)

/** A row with exactly the table's number of cells: missing ones are empty, extra ones are dropped (GFM). */
data class TableRow(val range: TextRange, val cells: List<TableCell>)

/** A GFM table as data: [span] runs from the first character of the header row to the last of the last row. */
data class TableModel(val span: TextRange, val header: TableRow, val rows: List<TableRow>, val alignments: List<ColumnAlignment>) {
    val columnCount: Int get() = alignments.size

    /** The same text in the same styles and alignments, wherever the two tables sit in their documents. */
    fun sameContent(other: TableModel): Boolean =
        alignments == other.alignments && header.cells.size == other.header.cells.size && rows.size == other.rows.size &&
            (listOf(header) + rows).zip(listOf(other.header) + other.rows).all { (a, b) -> a.cells.map { it.runs } == b.cells.map { it.runs } }
}

/**
 * Step Q1.1. Turns the Markdown plugin's table PSI into a [TableModel] — pure data, so the collector can build
 * it inside its read action and the renderer can lay it out later without touching the PSI again.
 */
object TableModelBuilder {

    fun build(table: MarkdownTable): TableModel {
        val text = table.text
        var end = text.length
        while (end > 0 && text[end - 1].isWhitespace()) end--
        val span = TextRange(table.textRange.startOffset, table.textRange.startOffset + end)
        val headerRow = table.headerRow ?: table.getRows(true).firstOrNull()
            ?: return TableModel(span, TableRow(span, emptyList()), emptyList(), emptyList())
        val columns = headerRow.cells.size
        val separator = PsiTreeUtil.getChildOfType(table, MarkdownTableSeparatorRow::class.java)
        val alignments = List(columns) { alignment(separator, it) }
        val header = row(headerRow, columns)
        val rows = table.getRows(false).filter { it !== headerRow }.map { row(it, columns) }
        return TableModel(span, header, rows, alignments)
    }

    private fun alignment(separator: MarkdownTableSeparatorRow?, column: Int): ColumnAlignment {
        if (separator == null || column >= separator.cellsCount) return ColumnAlignment.LEFT
        return when (separator.getCellAlignment(column)) {
            MarkdownTableSeparatorRow.CellAlignment.CENTER -> ColumnAlignment.CENTER
            MarkdownTableSeparatorRow.CellAlignment.RIGHT -> ColumnAlignment.RIGHT
            else -> ColumnAlignment.LEFT
        }
    }

    private fun row(row: MarkdownTableRow, columns: Int): TableRow {
        val range = row.textRange
        val cells = row.cells.take(columns).map { cell(it) }
        val missing = TextRange(range.endOffset, range.endOffset)
        return TableRow(range, cells + List(columns - cells.size) { TableCell(missing, emptyList()) })
    }

    private fun cell(cell: PsiElement): TableCell {
        val content = childElements(cell).dropWhile { it is PsiWhiteSpace }.dropLastWhile { it is PsiWhiteSpace }
        val range = if (content.isEmpty()) TextRange(cell.textRange.startOffset, cell.textRange.startOffset)
        else TextRange(content.first().textRange.startOffset, content.last().textRange.endOffset)
        val runs = RunBuilder()
        for (child in content) walk(child, Style(), runs)
        return TableCell(range, runs.finish())
    }

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val link: String? = null,
    )

    private class RunBuilder {
        private val runs = ArrayList<StyledRun>()
        private var pendingBreak = false

        fun append(text: String, style: Style) {
            if (text.isEmpty()) return
            val last = runs.lastOrNull()
            if (last != null && !pendingBreak && sameStyle(last, style)) {
                runs[runs.lastIndex] = last.copy(text = last.text + text)
                return
            }
            runs += StyledRun(text, style.bold, style.italic, style.strike, style.code, style.link, pendingBreak)
            pendingBreak = false
        }

        fun lineBreak() {
            pendingBreak = true
        }

        /** Whitespace at either end of the cell is not content; a `<br>` at either end is dropped with it. */
        fun finish(): List<StyledRun> {
            if (runs.isNotEmpty()) {
                runs[0] = runs[0].copy(text = runs[0].text.trimStart(), breakBefore = false)
                runs[runs.lastIndex] = runs[runs.lastIndex].copy(text = runs[runs.lastIndex].text.trimEnd())
            }
            return runs.filter { it.text.isNotEmpty() }
        }

        private fun sameStyle(run: StyledRun, style: Style): Boolean =
            run.bold == style.bold && run.italic == style.italic && run.strike == style.strike && run.code == style.code && run.link == style.link
    }

    private fun walk(element: PsiElement, style: Style, out: RunBuilder) {
        when (PsiUtilCore.getElementType(element)) {
            MarkdownElementTypes.STRONG -> children(element, MarkdownTokenTypes.EMPH).forEach { walk(it, style.copy(bold = true), out) }
            MarkdownElementTypes.EMPH -> children(element, MarkdownTokenTypes.EMPH).forEach { walk(it, style.copy(italic = true), out) }
            MarkdownElementTypes.STRIKETHROUGH -> children(element, MarkdownTokenTypes.TILDE).forEach { walk(it, style.copy(strike = true), out) }
            MarkdownElementTypes.CODE_SPAN -> {
                val code = children(element, MarkdownTokenTypes.BACKTICK).joinToString("") { it.text }.replace("\\|", "|")
                out.append(code, style.copy(code = true))
            }
            MarkdownElementTypes.INLINE_LINK -> {
                val destination = element.node.findChildByType(MarkdownElementTypes.LINK_DESTINATION)?.text
                val linkText = element.node.findChildByType(MarkdownElementTypes.LINK_TEXT)?.psi ?: return
                val linked = if (destination != null && style.link == null) style.copy(link = destination) else style
                for (child in children(linkText, MarkdownTokenTypes.LBRACKET, MarkdownTokenTypes.RBRACKET)) walk(child, linked, out)
            }
            MarkdownElementTypes.IMAGE -> {
                // Alt text only: the inner inline link is the image source, not a destination to follow.
                val link = element.node.findChildByType(MarkdownElementTypes.INLINE_LINK)?.psi ?: return
                val linkText = link.node.findChildByType(MarkdownElementTypes.LINK_TEXT)?.psi ?: return
                for (child in children(linkText, MarkdownTokenTypes.LBRACKET, MarkdownTokenTypes.RBRACKET)) walk(child, style, out)
            }
            MarkdownElementTypes.AUTOLINK -> {
                val url = element.node.findChildByType(MarkdownTokenTypes.AUTOLINK)?.text ?: element.text.removeSurrounding("<", ">")
                out.append(url, style.copy(link = style.link ?: url))
            }
            MarkdownTokenTypes.GFM_AUTOLINK -> out.append(element.text, style.copy(link = style.link ?: element.text))
            MarkdownTokenTypes.HTML_TAG -> if (BR.matches(element.text)) out.lineBreak() else out.append(element.text, style)
            else -> when {
                element is PsiWhiteSpace -> out.append(" ", style)
                element.firstChild == null -> out.append(unescape(element.text), style)
                else -> childElements(element).forEach { walk(it, style, out) }
            }
        }
    }

    /** Every child, leaves included: `PsiElement.getChildren()` on the plugin's PSI returns composites only. */
    private fun childElements(element: PsiElement): List<PsiElement> =
        generateSequence(element.firstChild) { it.nextSibling }.toList()

    private fun children(element: PsiElement, vararg skipped: IElementType): List<PsiElement> =
        childElements(element).filter { PsiUtilCore.getElementType(it) !in skipped }

    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    private val ESCAPE = Regex("\\\\([!-/:-@\\[-`{-~])")

    private fun unescape(text: String): String = ESCAPE.replace(text) { it.groupValues[1] }
}
