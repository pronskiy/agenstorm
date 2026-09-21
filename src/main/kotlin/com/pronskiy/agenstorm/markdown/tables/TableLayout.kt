package com.pronskiy.agenstorm.markdown.tables

import java.awt.Rectangle

/** What the layout needs from a font: a run's width in its style, and one line's height. */
interface TextMeasurer {
    val lineHeight: Int
    fun width(text: String, run: StyledRun): Int
}

/** Space around a cell's content, per side. */
data class Padding(val horizontal: Int, val vertical: Int)

/** One drawn fragment: [text] is the part of [run] on this line; [x] and [y] are the fragment's top-left within the table. */
data class PositionedRun(val run: StyledRun, val text: String, val x: Int, val y: Int, val width: Int)

data class ColumnGeometry(val x: Int, val width: Int)

data class RowGeometry(val y: Int, val height: Int)

/** A laid-out cell: [row] 0 is the header; [bounds] is the whole cell, padding included. */
data class CellGeometry(val row: Int, val column: Int, val cell: TableCell, val bounds: Rectangle, val runs: List<PositionedRun>)

/** What is under a point: the cell, and the fragment if the point is on text. */
data class Hit(val row: Int, val column: Int, val cell: TableCell, val run: PositionedRun?)

/** The result of [TableLayout.layout]; every coordinate is relative to the table's top-left corner. */
data class TableGeometry(
    val width: Int,
    val height: Int,
    val lineHeight: Int,
    val columns: List<ColumnGeometry>,
    val rows: List<RowGeometry>,
    val cells: List<CellGeometry>,
) {
    fun hitTest(x: Int, y: Int): Hit? {
        val cell = cells.firstOrNull { it.bounds.contains(x, y) } ?: return null
        val run = cell.runs.firstOrNull { x >= it.x && x < it.x + it.width && y >= it.y && y < it.y + lineHeight }
        return Hit(cell.row, cell.column, cell.cell, run)
    }
}

/**
 * Step Q1.2. Lays a [TableModel] out the way a browser's automatic table layout does, with no font in sight:
 * each column wants at most its widest unwrapped cell and at least its widest word; if everything fits the
 * columns take their natural width, otherwise the spare width is shared in proportion to how much each column
 * could still shrink, and when even the words do not fit the table is wider than the space it was given.
 * Cells wrap at word boundaries, a `<br>` forces a line, and a column's alignment shifts every line in it.
 */
object TableLayout {

    fun layout(model: TableModel, availableWidth: Int, measurer: TextMeasurer, padding: Padding): TableGeometry {
        val allRows = listOf(model.header) + model.rows
        val columnCount = model.columnCount
        if (columnCount == 0 || allRows.isEmpty()) return TableGeometry(0, 0, measurer.lineHeight, emptyList(), emptyList(), emptyList())
        val segments = allRows.map { row -> row.cells.map { segments(it, measurer) } }
        val minimum = IntArray(columnCount)
        val maximum = IntArray(columnCount)
        for (row in segments) for ((column, cell) in row.withIndex()) {
            minimum[column] = maxOf(minimum[column], widest(lines(cell, 0, measurer)))
            maximum[column] = maxOf(maximum[column], widest(lines(cell, Int.MAX_VALUE, measurer)))
        }
        val contentWidths = distribute(minimum, maximum, availableWidth - columnCount * 2 * padding.horizontal)

        val columns = ArrayList<ColumnGeometry>(columnCount)
        var x = 0
        for (width in contentWidths) {
            val outer = width + 2 * padding.horizontal
            columns += ColumnGeometry(x, outer)
            x += outer
        }
        val tableWidth = x

        val rows = ArrayList<RowGeometry>(allRows.size)
        val cells = ArrayList<CellGeometry>()
        var y = 0
        for ((rowIndex, row) in allRows.withIndex()) {
            val wrapped = row.cells.indices.map { lines(segments[rowIndex][it], contentWidths[it], measurer) }
            val height = maxOf(1, wrapped.maxOf { it.size }) * measurer.lineHeight + 2 * padding.vertical
            for ((column, cellLines) in wrapped.withIndex()) {
                val geometry = columns[column]
                val runs = ArrayList<PositionedRun>()
                for ((lineIndex, line) in cellLines.withIndex()) {
                    val lineWidth = line.sumOf { it.width }
                    val shift = when (model.alignments[column]) {
                        ColumnAlignment.LEFT -> 0
                        ColumnAlignment.CENTER -> maxOf(0, (contentWidths[column] - lineWidth) / 2)
                        ColumnAlignment.RIGHT -> maxOf(0, contentWidths[column] - lineWidth)
                    }
                    var runX = geometry.x + padding.horizontal + shift
                    val runY = y + padding.vertical + lineIndex * measurer.lineHeight
                    for (fragment in merge(line)) {
                        runs += PositionedRun(fragment.run, fragment.text, runX, runY, fragment.width)
                        runX += fragment.width
                    }
                }
                cells += CellGeometry(rowIndex, column, row.cells[column], Rectangle(geometry.x, y, geometry.width, height), runs)
            }
            rows += RowGeometry(y, height)
            y += height
        }
        return TableGeometry(tableWidth, y, measurer.lineHeight, columns, rows, cells)
    }

    /** A piece of a run that is never split: a word with the whitespace before it, or trailing whitespace. */
    private class Segment(val run: StyledRun, val text: String, val width: Int, val forced: Boolean, val breakable: Boolean)

    private val PIECES = Regex("\\s*\\S+|\\s+")

    private fun segments(cell: TableCell, measurer: TextMeasurer): List<Segment> {
        val out = ArrayList<Segment>()
        var previousEndsWithSpace = true
        for (run in cell.runs) {
            var forced = run.breakBefore
            for (match in PIECES.findAll(run.text)) {
                val piece = match.value
                val breakable = piece.first().isWhitespace() || previousEndsWithSpace
                out += Segment(run, piece, measurer.width(piece, run), forced, breakable)
                forced = false
                previousEndsWithSpace = piece.last().isWhitespace()
            }
        }
        return out
    }

    /** Greedy wrapping at [width]: a line breaks before a breakable segment that would overflow, or where a break is forced. */
    private fun lines(segments: List<Segment>, width: Int, measurer: TextMeasurer): List<List<Segment>> {
        val lines = ArrayList<MutableList<Segment>>()
        var line = ArrayList<Segment>()
        var x = 0L
        for (segment in segments) {
            if (segment.forced || (segment.breakable && line.isNotEmpty() && x + segment.width > width)) {
                lines += line
                line = ArrayList()
                x = 0
            }
            val placed = if (line.isEmpty()) trimStart(segment, measurer) else segment
            if (placed == null) continue
            line += placed
            x += placed.width
        }
        lines += line
        return lines.map { trimEnd(it, measurer) }.filter { it.isNotEmpty() || lines.size == 1 }
    }

    private fun trimStart(segment: Segment, measurer: TextMeasurer): Segment? {
        val text = segment.text.trimStart()
        if (text.isEmpty()) return null
        if (text.length == segment.text.length) return segment
        return Segment(segment.run, text, measurer.width(text, segment.run), segment.forced, segment.breakable)
    }

    private fun trimEnd(line: MutableList<Segment>, measurer: TextMeasurer): List<Segment> {
        while (line.isNotEmpty()) {
            val last = line.last()
            val text = last.text.trimEnd()
            if (text.length == last.text.length) break
            line.removeAt(line.lastIndex)
            if (text.isNotEmpty()) {
                line += Segment(last.run, text, measurer.width(text, last.run), last.forced, last.breakable)
                break
            }
        }
        return line
    }

    private fun widest(lines: List<List<Segment>>): Int = lines.maxOfOrNull { line -> line.sumOf { it.width } } ?: 0

    /** Adjacent segments of one run on one line become one fragment, so a word and its style are painted together. */
    private fun merge(line: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>()
        for (segment in line) {
            val last = out.lastOrNull()
            if (last != null && last.run === segment.run) {
                out[out.lastIndex] = Segment(last.run, last.text + segment.text, last.width + segment.width, last.forced, last.breakable)
            } else {
                out += segment
            }
        }
        return out
    }

    /**
     * Natural widths when they fit; otherwise a column no wider than an equal share of the space keeps its
     * natural width (a date or a name never wraps because a prose column is long), and the rest share what is
     * left in proportion to how much each can shrink. When even the longest words do not fit, every column
     * gets its minimum and the table overflows.
     */
    private fun distribute(minimum: IntArray, maximum: IntArray, available: Int): IntArray {
        val count = minimum.size
        if (maximum.sum() <= available) return maximum
        if (minimum.sum() >= available) return minimum
        val widths = IntArray(count)
        val flexible = ArrayList<Int>()
        var remaining = available
        val share = available / count
        for (column in 0 until count) {
            if (maximum[column] <= share) {
                widths[column] = maximum[column]
                remaining -= maximum[column]
            } else {
                flexible += column
            }
        }
        val flexibleMinimum = flexible.sumOf { minimum[it] }
        val flex = flexible.sumOf { maximum[it] - minimum[it] }
        val extra = maxOf(0, remaining - flexibleMinimum)
        for (column in flexible) {
            widths[column] = minimum[column] + if (flex == 0) 0 else ((maximum[column] - minimum[column]).toLong() * extra / flex).toInt()
        }
        val leftover = remaining - flexible.sumOf { widths[it] }
        if (leftover > 0 && flexible.isNotEmpty()) widths[flexible.maxBy { maximum[it] - minimum[it] }] += leftover
        return widths
    }
}
