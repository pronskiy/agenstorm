package com.pronskiy.agenstorm.markdown

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Step H1.1 over `testData/markdown/fences.md`: the ``` lines go, the body never does, and each fence
 * yields one block carrying its span and its info string. Covers the six cases the step names — no info
 * string, an unknown one, `~~~`, a fence indented in a list, one inside a block quote, and one left
 * unterminated at the end of the file.
 */
class CodeFenceCollectorTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData/markdown"

    fun testFenceLinesAreHiddenAndBodiesAreUntouched() {
        val markup = collectFixture()
        // A list of lines, not a raw string: two of the header rows keep a prefix ("  " and "> ") that a
        // trimIndent literal would hide.
        val expected = listOf(
            "",                             // ```php  -> the card's header row, its EOL kept
            "echo **not** markup;",
            "",                             // the closing ``` -> the card's footer row, its line number kept
            "",                             // the blank line between the fences
            "",                             // ``` with no info string
            "no info string",
            "",
            "",
            "",                             // ```mystery-lang
            "unknown info string",
            "",
            "",
            "",                             // ~~~js
            "tilde fence",
            "",
            "",
            "\u2022 a list item",
            "  ",                           // ```sh indented in the list item keeps the indent
            "  indented in a list",
            "",                             // the closing token carries the line's indent, so the row is empty
            "",
            "> ",                           // ```yaml inside the quote keeps the quote marker
            "> quoted: fence",
            "",                             // and its `> ` likewise
            "",
            "",                             // ```php, never closed
            "unterminated at end of file",
        ).joinToString("\n", postfix = "\n")

        assertEquals(expected, render(myFixture.file.text, markup.ranges))
    }

    fun testEveryFenceBecomesOneBlockWithItsInfoString() {
        val markup = collectFixture()

        assertEquals(
            listOf("php", null, "mystery-lang", "js", "sh", "yaml", "php"),
            markup.blocks.map { it.language },
        )
        assertTrue(markup.blocks.all { it.kind == MarkdownBlockKind.CODE_FENCE })
        val text = myFixture.file.text
        assertTrue(markup.blocks.first().span.substring(text).startsWith("```php"))
        assertTrue(markup.blocks.first().span.substring(text).endsWith("```"))
    }

    fun testAnUnterminatedFenceEmitsOnlyItsOpener() {
        val markup = collectFixture()
        val last = markup.blocks.last()
        val inLast = markup.ranges.filter { it.span == last.span }

        assertEquals(listOf(MarkupKind.FENCE_OPEN), inLast.map { it.kind })
    }

    fun testBothFenceLinesKeepTheirEol() {
        val text = myFixture.configureByText("a.md", "```php\nbody\n```\nafter\n").text
        val markup = MarkupRangeCollector.collectMarkup(myFixture.file)

        val (open, close) = markup.ranges.filter { it.kind.isFence }
        assertEquals("```php", open.range.substring(text))
        assertEquals("```", close.range.substring(text))
        assertEquals("both lines keep their EOL, so neither loses its number", "\nbody\n\nafter\n", render(text, markup.ranges))
    }

    fun testBothMarkersShareTheWholeFenceAsTheirSpan() {
        val text = myFixture.configureByText("a.md", "```\nbody\n```\n").text
        val markup = MarkupRangeCollector.collectMarkup(myFixture.file)

        assertEquals(2, markup.ranges.size)
        for (range in markup.ranges) {
            assertEquals("```\nbody\n```", range.span.substring(text))
            assertTrue(range.kind.isBlock)
            assertEquals("", range.placeholder)
        }
    }

    fun testFencesStayRawWhenTheOptionIsOff() {
        myFixture.configureByFile("fences.md")
        val markup = MarkupRangeCollector.collectMarkup(myFixture.file, MarkupRangeCollector.Options(codeBlocks = false))

        assertEmpty(markup.ranges.filter { it.kind.isFence })
        assertEmpty(markup.blocks)
    }

    fun testTheBodyOfAFenceIsNeverCollected() {
        myFixture.configureByText("a.md", "```md\n**bold** and `code` and # heading\n```\n")
        val markup = MarkupRangeCollector.collectMarkup(myFixture.file)

        assertEquals(listOf(MarkupKind.FENCE_OPEN, MarkupKind.FENCE_CLOSE), markup.ranges.map { it.kind })
    }

    private fun collectFixture(): Markup {
        myFixture.configureByFile("fences.md")
        return MarkupRangeCollector.collectMarkup(myFixture.file)
    }

    private fun render(text: String, ranges: List<MarkupRange>): String {
        val sb = StringBuilder(text)
        for (range in ranges.sortedByDescending { it.range.startOffset }) {
            sb.replace(range.range.startOffset, range.range.endOffset, range.placeholder)
        }
        return sb.toString()
    }
}
