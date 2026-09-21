package com.pronskiy.agenstorm.markdown

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step Q2.1 over `testData/markdown/tables.md`: every table yields one [MarkupKind.TABLE] range that takes the
 * line break before the table when there is one, plus its [com.pronskiy.agenstorm.markdown.tables.TableModel]
 * on [Markup.tables]; the inline markup inside the cells is still collected and nests inside the table's range.
 */
class TableCollectorTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData/markdown"

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testATableTakesTheLineBreakBeforeItAndEndsWithItsLastRow() {
        val markup = collectFixture()
        val text = myFixture.file.text
        val conference = tableRanges(markup)[1]
        assertEquals('\n', text[conference.range.startOffset])
        assertTrue(text.substring(conference.range.startOffset + 1).startsWith("| Conference |"))
        assertTrue(text.substring(0, conference.range.endOffset).endsWith("Speaking inclusion requires confirmation. |"))
        assertEquals("", conference.placeholder)
        assertEquals(conference.range, conference.span)
        assertTrue(MarkupKind.TABLE.isBlock)
    }

    fun testATableAtTheStartOfTheFileStartsAtItsFirstPipe() {
        val first = tableRanges(collectFixture()).first()
        assertEquals(0, first.range.startOffset)
    }

    fun testATableAfterAListBulletOrAQuoteMarkerStartsAtItsFirstPipe() {
        val markup = collectFixture()
        val text = myFixture.file.text
        val inList = tableRanges(markup)[3]
        val inQuote = tableRanges(markup)[4]
        assertEquals("| in list", text.substring(inList.range.startOffset, inList.range.startOffset + 9))
        assertEquals("| in quote", text.substring(inQuote.range.startOffset, inQuote.range.startOffset + 10))
    }

    fun testTheModelsRideAlongInDocumentOrder() {
        val markup = collectFixture()
        assertEquals(6, markup.tables.size)
        assertEquals(tableRanges(markup).map { it.range.endOffset }, markup.tables.map { it.span.endOffset })
        assertEquals("Conference", markup.tables[1].header.cells[0].runs.single().text)
    }

    fun testInlineMarkupInsideCellsNestsInsideTheTableRange() {
        val markup = collectFixture()
        val conference = tableRanges(markup)[1].range
        val inside = markup.ranges.filter { it.kind != MarkupKind.TABLE && conference.contains(it.range) }
        assertTrue(inside.any { it.kind == MarkupKind.STRONG })
        assertTrue(inside.any { it.kind == MarkupKind.LINK_TAIL })
    }

    fun testQuoteMarkersOnTableRowsAreStillFolded() {
        val markup = collectFixture()
        val text = myFixture.file.text
        val inQuote = tableRanges(markup)[4].range
        val markers = markup.ranges.filter { it.kind == MarkupKind.QUOTE_MARKER && inQuote.contains(it.range) }
        assertEquals(listOf(">"), markers.map { text.substring(it.range.startOffset, it.range.endOffset) })
    }

    fun testTheTablesOptionSwitchesBothOff() {
        myFixture.configureByFile("tables.md")
        val markup = MarkupRangeCollector.collectMarkup(myFixture.file, MarkupRangeCollector.Options(tables = false))
        assertEmpty(tableRanges(markup))
        assertEmpty(markup.tables)
        assertTrue(markup.ranges.any { it.kind == MarkupKind.STRONG })
    }

    fun testTheOptionReadsTheSetting() {
        assertTrue(MarkupRangeCollector.Options.fromSettings().tables)
        AgenstormSettings.getInstance().state.liveMarkupTables = false
        assertFalse(MarkupRangeCollector.Options.fromSettings().tables)
    }

    private fun collectFixture(): Markup {
        myFixture.configureByFile("tables.md")
        return MarkupRangeCollector.collectMarkup(myFixture.file)
    }

    private fun tableRanges(markup: Markup): List<MarkupRange> = markup.ranges.filter { it.kind == MarkupKind.TABLE }
}
