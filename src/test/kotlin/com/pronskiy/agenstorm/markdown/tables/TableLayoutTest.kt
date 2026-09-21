package com.pronskiy.agenstorm.markdown.tables

import com.intellij.openapi.util.TextRange
import junit.framework.TestCase

/**
 * Step Q1.2. Pure layout against a measurer that charges seven pixels per character whatever the style and
 * ten per line, with four pixels of horizontal and two of vertical padding, so every number below is exact.
 */
class TableLayoutTest : TestCase() {

    fun testNaturalWidthsWhenEverythingFits() {
        val geometry = layout(table(listOf("ab", "abcd"), listOf(listOf("a", "b"))), 1000)
        assertEquals(listOf(0 to 22, 22 to 36), geometry.columns.map { it.x to it.width })
        assertEquals(58, geometry.width)
        assertEquals(listOf(0 to 14, 14 to 14), geometry.rows.map { it.y to it.height })
        assertEquals(28, geometry.height)
    }

    fun testTheHeaderWidensItsColumn() {
        val geometry = layout(table(listOf("Conference"), listOf(listOf("x"))), 1000)
        assertEquals(70 + 8, geometry.columns.single().width)
    }

    fun testWrapsAndSharesTheWidthWhenTheTableIsTooWide() {
        // Column A: "aaaa bb" is 49 wide, its longest word 28; column B: "cc dd" is 35, longest word 14.
        // Outer widths: 100 at most, 58 at least; at 80 the 22 spare pixels go 11 to each.
        val geometry = layout(table(listOf("aaaa bb", "cc dd"), emptyList()), 80)
        assertEquals(listOf(39 + 8, 25 + 8), geometry.columns.map { it.width })
        assertEquals(80, geometry.width)
        val a = geometry.cells[0]
        assertEquals(listOf("aaaa", "bb"), a.runs.map { it.text })
        assertEquals(listOf(2, 12), a.runs.map { it.y })
        assertEquals(listOf("cc", "dd"), geometry.cells[1].runs.map { it.text })
        assertEquals(24, geometry.rows.single().height)
    }

    fun testAColumnNarrowerThanAnEqualShareKeepsItsWidthWhileTheLongOnesWrap() {
        // Content: "12 Jan" is 42 wide, the two prose columns 119 each. At 150 of content width an equal share is
        // 50, so the date keeps its 42; the other two share the remaining 108 in proportion to what they can shrink.
        val geometry = layout(table(listOf("12 Jan", "long text here ok", "another long text"), emptyList()), 150 + 3 * 8)
        assertEquals(listOf(42 + 8, 46 + 8, 62 + 8), geometry.columns.map { it.width })
        assertEquals(1, geometry.cells[0].runs.map { it.y }.distinct().size)
    }

    fun testWordsNeverBreakSoTheTableCanBeWiderThanTheViewport() {
        val geometry = layout(table(listOf("abcdefghij"), emptyList()), 30)
        assertEquals(78, geometry.width)
        assertEquals(listOf("abcdefghij"), geometry.cells.single().runs.map { it.text })
    }

    fun testAForcedBreakStartsANewLineAndDoesNotWidenTheColumn() {
        val cell = TableCell(TextRange.EMPTY_RANGE, listOf(StyledRun("one"), StyledRun("two", breakBefore = true)))
        val model = TableModel(TextRange.EMPTY_RANGE, TableRow(TextRange.EMPTY_RANGE, listOf(cell)), emptyList(), listOf(ColumnAlignment.LEFT))
        val geometry = layout(model, 1000)
        assertEquals(21 + 8, geometry.columns.single().width)
        assertEquals(listOf(2, 12), geometry.cells.single().runs.map { it.y })
    }

    fun testWrapsAtWordBoundariesAcrossStyledRuns() {
        val cell = TableCell(
            TextRange.EMPTY_RANGE,
            listOf(StyledRun("deadline was "), StyledRun("14 September", bold = true), StyledRun("; now")),
        )
        val model = TableModel(TextRange.EMPTY_RANGE, TableRow(TextRange.EMPTY_RANGE, listOf(cell)), emptyList(), listOf(ColumnAlignment.LEFT))
        // "deadline was 14 September; now" is 30 characters; at 16 characters of content the lines are
        // "deadline was 14" / "September; now".
        val geometry = layout(model, 16 * 7 + 8)
        val lines = geometry.cells.single().runs.groupBy { it.y }.values.map { line -> line.joinToString("") { it.text } }
        assertEquals(listOf("deadline was 14", "September; now"), lines)
        val bold = geometry.cells.single().runs.filter { it.run.bold }
        assertEquals(listOf("14", "September"), bold.map { it.text })
        assertEquals(listOf(4 + 13 * 7, 4), bold.map { it.x })
    }

    fun testAlignmentShiftsTheRuns() {
        val model = TableModel(
            TextRange.EMPTY_RANGE,
            row("abcdef", "abcdef"),
            listOf(row("ab", "ab")),
            listOf(ColumnAlignment.CENTER, ColumnAlignment.RIGHT),
        )
        val geometry = layout(model, 1000)
        val centered = geometry.cells.single { it.row == 1 && it.column == 0 }.runs.single()
        val right = geometry.cells.single { it.row == 1 && it.column == 1 }.runs.single()
        assertEquals(4 + 14, centered.x)
        assertEquals(50 + 4 + 28, right.x)
    }

    fun testHitTestFindsTheCellAndTheRunUnderAPoint() {
        val geometry = layout(table(listOf("ab", "cd"), listOf(listOf("ef", "gh"))), 1000)
        val onRun = geometry.hitTest(22 + 4 + 3, 14 + 2 + 5)
        assertEquals(1 to 1, onRun?.row to onRun?.column)
        assertEquals("gh", onRun?.run?.text)
        val inPadding = geometry.hitTest(22 + 1, 14 + 7)
        assertEquals(1 to 1, inPadding?.row to inPadding?.column)
        assertNull(inPadding?.run)
        assertNull(geometry.hitTest(200, 5))
        assertNull(geometry.hitTest(5, 200))
    }

    private fun layout(model: TableModel, width: Int): TableGeometry =
        TableLayout.layout(model, width, SevenPixels, Padding(horizontal = 4, vertical = 2))

    private object SevenPixels : TextMeasurer {
        override val lineHeight = 10
        override fun width(text: String, run: StyledRun) = text.length * 7
    }

    private fun table(header: List<String>, rows: List<List<String>>): TableModel =
        TableModel(TextRange.EMPTY_RANGE, row(*header.toTypedArray()), rows.map { row(*it.toTypedArray()) }, header.map { ColumnAlignment.LEFT })

    private fun row(vararg cells: String): TableRow =
        TableRow(TextRange.EMPTY_RANGE, cells.map { TableCell(TextRange.EMPTY_RANGE, listOf(StyledRun(it))) })
}
