package com.pronskiy.agenstorm.markdown.tables

import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownTable

/**
 * Step Q1.1 over `testData/markdown/tables.md`: every table becomes a [TableModel] — a header row, body rows
 * made rectangular the GFM way, one alignment per column, and cells whose content range is trimmed and whose
 * text is a list of styled runs.
 */
class TableModelBuilderTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData/markdown"

    fun testHeaderRowsAndColumnsOfTheConferenceTable() {
        val table = conference()
        assertEquals(4, table.columnCount)
        assertEquals(listOf("Conference", "When / where", "Why it fits Omnigraph", "Speaking and sponsorship route"), table.header.cells.map { it.text })
        assertEquals(3, table.rows.size)
        assertEquals(List(4) { ColumnAlignment.LEFT }, table.alignments)
    }

    fun testCellRangeIsTheTrimmedContent() {
        val cell = conference().header.cells[0]
        val text = myFixture.file.text
        assertEquals("Conference", text.substring(cell.range.startOffset, cell.range.endOffset))
    }

    fun testSpanRunsFromTheFirstRowToTheLastRow() {
        val span = conference().span
        val text = myFixture.file.text
        assertTrue(text.substring(span.startOffset).startsWith("| Conference |"))
        assertTrue(text.substring(0, span.endOffset).endsWith("Speaking inclusion requires confirmation. |"))
    }

    fun testBoldRunInsideASentenceSplitsTheText() {
        val cell = conference().rows[0].cells[3]
        val runs = cell.runs
        assertEquals(StyledRun("Explicit vendor sponsorship route.", bold = true), runs[0])
        assertEquals(StyledRun(" Vendor presentation submissions are directed to sponsorship. Regular submission deadline was "), runs[1])
        assertEquals(StyledRun("14 September", bold = true), runs[2])
        assertEquals(StyledRun("; approach the sponsorship team now. "), runs[3])
        assertEquals(StyledRun("Rules", link = "https://example.com/rules"), runs[4])
        assertEquals(StyledRun(", "), runs[5])
        assertEquals(StyledRun("sponsorship", link = "https://example.com/sponsor"), runs[6])
        assertEquals(StyledRun("."), runs[7])
        assertEquals(8, runs.size)
    }

    fun testLinkTextIsShownAndTheDestinationCarried() {
        val cell = conference().rows[1].cells[3]
        val link = cell.runs.single { it.link != null }
        assertEquals("sponsorships@ai.engineer", link.text)
        assertEquals("mailto:sponsorships@ai.engineer", link.link)
    }

    fun testAlignmentsComeFromTheSeparatorRow() {
        assertEquals(listOf(ColumnAlignment.CENTER, ColumnAlignment.RIGHT), shapes().alignments)
    }

    fun testBrForcesALineBreak() {
        assertEquals(listOf(StyledRun("one"), StyledRun("two", breakBefore = true)), shapeCell("break").runs)
    }

    fun testEscapedPipeIsAPipe() {
        assertEquals(listOf(StyledRun("a | b")), shapeCell("pipe").runs)
    }

    fun testCodeItalicAndStrikeRuns() {
        assertEquals(
            listOf(
                StyledRun("x | y", code = true),
                StyledRun(" and "),
                StyledRun("i", italic = true),
                StyledRun(" "),
                StyledRun("s", strike = true),
            ),
            shapeCell("code").runs,
        )
    }

    fun testAutolinksLinkAndImagesShowTheirAltText() {
        assertEquals(
            listOf(
                StyledRun("https://x.y", link = "https://x.y"),
                StyledRun(" "),
                StyledRun("https://z.z", link = "https://z.z"),
                StyledRun(" alt"),
            ),
            shapeCell("links").runs,
        )
    }

    fun testRaggedRowsAreMadeRectangular() {
        val short = shapeRow("ragged")
        val text = myFixture.file.text
        assertEquals(2, short.cells.size)
        assertEquals(emptyList<StyledRun>(), short.cells[1].runs)
        assertEquals(TextRange(short.range.endOffset, short.range.endOffset), short.cells[1].range)
        val long = shapeRow("extra")
        assertEquals(listOf("extra", "1"), long.cells.map { it.text })
        assertEquals("1", text.substring(long.cells[1].range.startOffset, long.cells[1].range.endOffset))
    }

    fun testTablesInAListItemAndInAQuote() {
        val all = tables()
        val inList = all[3]
        assertEquals(listOf("in list", "b"), inList.header.cells.map { it.text })
        assertEquals(listOf("1", "2"), inList.rows.single().cells.map { it.text })
        val inQuote = all[4]
        assertEquals(listOf("in quote", "b"), inQuote.header.cells.map { it.text })
        assertEquals(0, inQuote.rows.size)
        assertEquals(listOf(ColumnAlignment.LEFT, ColumnAlignment.LEFT), inQuote.alignments)
    }

    fun testTableAtTheStartOfTheFileAndAtItsEndWithoutANewline() {
        val all = tables()
        assertEquals(0, all.first().span.startOffset)
        assertEquals(myFixture.file.textLength, all.last().span.endOffset)
        assertEquals(listOf("no", "newline"), all.last().rows.single().cells.map { it.text })
    }

    private fun tables(): List<TableModel> {
        myFixture.configureByFile("tables.md")
        return PsiTreeUtil.findChildrenOfType(myFixture.file, MarkdownTable::class.java).map(TableModelBuilder::build)
    }

    private fun conference(): TableModel = tables()[1]

    private fun shapes(): TableModel = tables()[2]

    private fun shapeRow(name: String): TableRow = shapes().rows.single { it.cells[0].text == name }

    private fun shapeCell(name: String): TableCell = shapeRow(name).cells[1]

    private val TableCell.text: String get() = runs.joinToString("") { it.text }
}
