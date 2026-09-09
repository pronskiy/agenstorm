package com.pronskiy.agenstorm.markdown

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Step H3.1 over `testData/markdown/quotes.md`: every `>` becomes a space, whatever the parser did with it,
 * the content of a quote is still folded like any other Markdown, and each quote yields one card — the
 * outermost one, however deeply the inner quotes nest.
 */
class BlockQuoteCollectorTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData/markdown"

    fun testEveryQuoteMarkerIsHiddenAndTheColumnsDoNotMove() {
        val markup = collectFixture()

        // Every `>` is one space wide, so each line keeps the column its nesting and spacing gave it.
        val expected = listOf(
            "  A quote with bold and a link.",
            "  It wraps onto a second line.",
            " ",
            "  \u2022 a list inside the quote",
            "  \u2022 and another item",
            " ",
            "  ",                                  // > ```php  -> the fence card's header row
            "  echo \"fenced inside a quote\";",
            "",                                    // the closing ``` is one token from the head of its line,
                                                   // the `>` included, so the whole row goes
            "   a nested quote",                   // >>
            "    spaced nesting",                  // > >
            "",
            "Plain paragraph between the quotes.",
            "",
            " no space after the marker",          // >text, no space to keep
            "",
            "    indented by two spaces",          // the two spaces before the > survive
            "",                                    // the file's trailing newline
        )

        assertEquals(expected, render(myFixture.file.text, markup.ranges).lines())
    }

    fun testAMarkerIsOneCharacterReplacedByOneSpace() {
        val markup = collectFixture()
        val markers = markup.ranges.filter { it.kind == MarkupKind.QUOTE_MARKER }

        assertTrue(markers.isNotEmpty())
        for (marker in markers) {
            assertEquals(1, marker.range.length)
            assertEquals(">", myFixture.file.text.substring(marker.range.startOffset, marker.range.endOffset))
            assertEquals(MarkupRangeCollector.QUOTE_MARKER_PLACEHOLDER, marker.placeholder)
        }
    }

    fun testTheContentOfAQuoteIsStillFolded() {
        val markup = collectFixture()
        val kinds = markup.ranges.map { it.kind }.toSet()

        assertTrue("bold inside a quote must still fold", MarkupKind.STRONG in kinds)
        assertTrue("a link inside a quote must still fold", MarkupKind.LINK_OPEN in kinds)
        assertTrue("a bullet inside a quote must still become •", MarkupKind.BULLET in kinds)
        assertTrue("a fence inside a quote must still lose its markers", MarkupKind.FENCE_OPEN in kinds)
    }

    fun testOnlyTheOutermostQuoteGetsACard() {
        val markup = collectFixture()
        val quotes = markup.blocks.filter { it.kind == MarkdownBlockKind.BLOCK_QUOTE }
        val text = myFixture.file.text

        assertEquals(3, quotes.size)
        for (quote in quotes) assertTrue("a card starts at a `>`", text[quote.span.startOffset] == '>' || text[quote.span.startOffset] == ' ')
        // The nested quote sits inside the first card's span rather than adding one of its own.
        assertTrue(quotes[0].span.contains(text.indexOf("a nested quote")))
        assertNull("only a fence carries a language", quotes.first().language)
    }

    fun testTheOptionOffLeavesQuotesAlone() {
        myFixture.configureByFile("quotes.md")
        val markup = MarkupRangeCollector.collectMarkup(myFixture.file, MarkupRangeCollector.Options(blockQuotes = false))

        assertEmpty(markup.ranges.filter { it.kind == MarkupKind.QUOTE_MARKER })
        assertEmpty(markup.blocks.filter { it.kind == MarkdownBlockKind.BLOCK_QUOTE })
        assertTrue("the rest of the markup is untouched", markup.ranges.any { it.kind == MarkupKind.STRONG })
    }

    fun testTheMarkerScanReadsOnlyTheQuotePrefix() {
        val text = "> one\n>> two\n> > three\nlazy > not a marker\n>\n"

        assertEquals(listOf(0, 6, 7, 13, 15, 43), MarkupRangeCollector.quoteMarkerOffsets(text, 0, text.length))
    }

    fun testAMarkerIsARevealedBlockMarker() {
        assertTrue("the whole caret line reveals, as for headings and bullets", MarkupKind.QUOTE_MARKER.isBlock)
    }

    fun testEveryMarkerOfAQuoteSharesTheQuotesSpan() {
        val markup = collectFixture()
        val quote = markup.blocks.first { it.kind == MarkdownBlockKind.BLOCK_QUOTE }
        val markers = markup.ranges.filter { it.kind == MarkupKind.QUOTE_MARKER && quote.span.contains(it.range) }

        assertTrue("the first quote of the fixture spans several lines", markers.size > 3)
        // One span means one FoldingGroup, and the controller reveals a group as a whole.
        assertEquals(setOf(quote.span), markers.map { it.span }.toSet())
    }

    fun testAGithubAlertIsTreatedAsAQuote() {
        myFixture.configureByText("a.md", "> [!IMPORTANT]\n> The challenge is **live**.\n> - an item\n")
        val markup = MarkupRangeCollector.collectMarkup(myFixture.file)

        // The parser gives an alert its own element, but its lines carry the same `>` markers.
        assertEquals(3, markup.ranges.count { it.kind == MarkupKind.QUOTE_MARKER })
        val card = markup.blocks.single { it.kind == MarkdownBlockKind.BLOCK_QUOTE }
        assertEquals(0, card.span.startOffset)
        assertEquals(
            listOf("  [!IMPORTANT]", "  The challenge is live.", "  \u2022 an item", ""),
            render(myFixture.file.text, markup.ranges).lines(),
        )
    }

    fun testAnAlertTitleIsLeftExactlyAsWritten() {
        myFixture.configureByText("a.md", "> [!WARNING]\n> careful\n")
        val markup = MarkupRangeCollector.collectMarkup(myFixture.file)
        val title = myFixture.file.text.indexOf("[!WARNING]")

        assertEmpty(
            "the plugin styles the title and puts its icon in the gutter; we must not fold it",
            markup.ranges.filter { it.range.startOffset >= title && it.range.endOffset <= title + "[!WARNING]".length },
        )
    }

    fun testAnAlertMarkersAlsoShareOneSpan() {
        myFixture.configureByText("a.md", "> [!NOTE]\n> one\n> two\n")
        val markup = MarkupRangeCollector.collectMarkup(myFixture.file)
        val markers = markup.ranges.filter { it.kind == MarkupKind.QUOTE_MARKER }

        assertEquals(3, markers.size)
        assertEquals(1, markers.map { it.span }.toSet().size)
    }

    fun testAnAlertInsideAQuoteDoesNotAddASecondCard() {
        myFixture.configureByText("a.md", "> outer\n>\n> > [!TIP]\n> > nested\n")
        val markup = MarkupRangeCollector.collectMarkup(myFixture.file)

        assertEquals(1, markup.blocks.count { it.kind == MarkdownBlockKind.BLOCK_QUOTE })
    }

    fun testAMarkerAnotherRangeAlreadyHidesIsDropped() {
        val markup = collectFixture()
        val sorted = markup.ranges.sortedBy { it.range.startOffset }

        for ((left, right) in sorted.zipWithNext()) {
            assertTrue(
                "ranges must stay disjoint, but $left overlaps $right",
                left.range.endOffset <= right.range.startOffset,
            )
        }
        // The closing ``` of the quoted fence is one token from the head of its line, so its `>` is not folded twice.
        val closing = markup.ranges.single { it.kind == MarkupKind.FENCE_CLOSE }
        assertEmpty(markup.ranges.filter { it.kind == MarkupKind.QUOTE_MARKER && closing.range.contains(it.range) })
    }

    private fun collectFixture(): Markup {
        myFixture.configureByFile("quotes.md")
        return MarkupRangeCollector.collectMarkup(myFixture.file)
    }

    /** The document as the reader sees it: every folded range replaced by its placeholder. */
    private fun render(text: String, ranges: List<MarkupRange>): String {
        val sb = StringBuilder(text)
        for (range in ranges.sortedByDescending { it.range.startOffset }) {
            sb.replace(range.range.startOffset, range.range.endOffset, range.placeholder)
        }
        return sb.toString()
    }
}
