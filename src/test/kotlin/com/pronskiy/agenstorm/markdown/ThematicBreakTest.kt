package com.pronskiy.agenstorm.markdown

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step H3.3: a thematic break loses its characters and gets a rule drawn across the row they left empty —
 * whichever of `---`, `***`, `___` or `- - -` was written, and wherever it sits.
 */
class ThematicBreakTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testEverySpellingOfABreakIsHidden() {
        myFixture.configureByText("a.md", "before\n\n---\n\n***\n\n___\n\n- - -\n\nafter\n")
        val markup = MarkupRangeCollector.collectMarkup(myFixture.file)
        val rules = markup.ranges.filter { it.kind == MarkupKind.RULE }
        val text = myFixture.file.text

        assertEquals(4, rules.size)
        assertEquals(
            listOf("---", "***", "___", "- - -"),
            rules.map { text.substring(it.range.startOffset, it.range.endOffset) },
        )
        assertTrue("the whole token goes, and nothing stands in for it", rules.all { it.placeholder.isEmpty() })
    }

    fun testTheRowStaysBehindEmpty() {
        myFixture.configureByText("a.md", "before\n\n---\n\nafter\n")
        val markup = MarkupRangeCollector.collectMarkup(myFixture.file)

        // A document cannot lose a line, so the break's row is left empty — and keeps its number in the gutter.
        assertEquals(
            listOf("before", "", "", "", "after", ""),
            render(myFixture.file.text, markup.ranges).lines(),
        )
    }

    fun testEachBreakIsOneBlockOverItsOwnToken() {
        myFixture.configureByText("a.md", "---\n\n***\n")
        val markup = MarkupRangeCollector.collectMarkup(myFixture.file)
        val breaks = markup.blocks.filter { it.kind == MarkdownBlockKind.THEMATIC_BREAK }

        assertEquals(2, breaks.size)
        assertEquals(0 to 3, breaks[0].span.startOffset to breaks[0].span.endOffset)
        assertEquals(5 to 8, breaks[1].span.startOffset to breaks[1].span.endOffset)
        assertNull("only a fence carries a language", breaks[0].language)
    }

    fun testABreakInsideAQuoteKeepsBothTheQuotesCardAndItsOwnLine() {
        myFixture.configureByText("a.md", "> above\n>\n> ---\n>\n> below\n")
        val markup = MarkupRangeCollector.collectMarkup(myFixture.file)

        assertEquals(1, markup.blocks.count { it.kind == MarkdownBlockKind.BLOCK_QUOTE })
        assertEquals(1, markup.blocks.count { it.kind == MarkdownBlockKind.THEMATIC_BREAK })
        // The quote's `>` and the rule's `---` are separate tokens, so neither swallows the other.
        assertEquals(
            listOf("  above", " ", "  ", " ", "  below", ""),
            render(myFixture.file.text, markup.ranges).lines(),
        )
    }

    /** `---` straight under a line of text is that line's underline, not a break — and must stay as written. */
    fun testDashesUnderAParagraphAreASetextHeadingNotABreak() {
        myFixture.configureByText("a.md", "a heading\n---\n\nbody\n")
        val markup = MarkupRangeCollector.collectMarkup(myFixture.file)

        assertEmpty(markup.ranges.filter { it.kind == MarkupKind.RULE })
        assertEmpty(markup.blocks)
    }

    fun testTheOptionOffLeavesBreaksAlone() {
        myFixture.configureByText("a.md", "---\n")
        val markup = MarkupRangeCollector.collectMarkup(myFixture.file, MarkupRangeCollector.Options(rules = false))

        assertEmpty(markup.ranges.filter { it.kind == MarkupKind.RULE })
        assertEmpty(markup.blocks.filter { it.kind == MarkdownBlockKind.THEMATIC_BREAK })
    }

    fun testABreakIsARevealedBlockMarker() {
        assertTrue("the caret on its line brings the characters back", MarkupKind.RULE.isBlock)
    }

    fun testABreakIsDrawnAsALineAndNeverAsACard() {
        myFixture.configureByText("a.md", "---\n\n> quoted\n")
        val controller = LiveMarkupService.getInstance(project).controllerFor(myFixture.editor)!!
        controller.syncNow()

        val (rule, quote) = controller.blockHighlighters().sortedBy { it.startOffset }
        assertNull("a rule is a line on the row, not a card behind it", rule.getTextAttributes(myFixture.editor.colorsScheme)?.backgroundColor)
        assertNotNull(rule.customRenderer)
        assertNotNull("a quote still gets both", quote.getTextAttributes(myFixture.editor.colorsScheme)?.backgroundColor)
        assertNotNull(quote.customRenderer)
    }

    fun testTheRuleSitsInTheMiddleOfItsRow() {
        val bounds = MarkdownBlockRenderer.ruleBounds(topY = 40, lineHeight = 20, x = 7, width = 300)

        assertEquals(7, bounds.x)
        assertEquals(300, bounds.width)
        assertTrue("a divider, not a band", bounds.height in 1..4)
        assertTrue("centred in the row", bounds.y > 40 && bounds.y + bounds.height < 60)
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
