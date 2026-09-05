package com.pronskiy.agenstorm.markdown

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Step F1.1 / F1.5: the collector over `testData/markdown/collector.md`, checked the way a user sees it — every
 * range replaced by its placeholder — plus the kinds, order and the constructs that must stay raw.
 */
class MarkupRangeCollectorTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData/markdown"

    fun testFixtureRendersLikeObsidian() {
        val ranges = collectFixture()
        val expected = """
            Heading one
            Two
            Some bold and em and em2 and bold2 and gone and code and  a ` b  here.
            both text
            A link b and ![img](pic.png) and <https://auto.link>.
            - ☐ todo
            - ☑ done
            * ☑ DONE
            1. numbered x

            ```php
            **not** `here`
            ```

                **indented** code

            <div>**html**</div>

            > quote q

            | a | b |
            |---|---|
            | c | d |

        """.trimIndent()
        assertEquals(expected, render(myFixture.file.text, ranges))
    }

    fun testRangesAreSortedAndDisjoint() {
        val ranges = collectFixture()
        assertTrue(ranges.size > 10)
        for ((previous, next) in ranges.zipWithNext()) {
            assertTrue("$previous then $next", previous.range.endOffset <= next.range.startOffset)
        }
    }

    fun testKindsCoverExactlyTheMarkers() {
        val text = myFixture.configureByFile("collector.md").text
        val byKind = MarkupRangeCollector.collect(myFixture.file).groupBy({ it.kind }, { it.range.substring(text) })
        assertEquals(listOf("# ", "## ", " ##"), byKind[MarkupKind.HEADING])
        assertEquals(listOf("**", "**", "__", "__") + List(8) { "**" }, byKind[MarkupKind.STRONG])
        assertEquals(listOf("*", "*", "_", "_", "*", "*", "*", "*", "*", "*"), byKind[MarkupKind.EMPH])
        assertEquals(listOf("~~", "~~"), byKind[MarkupKind.STRIKE])
        assertEquals(listOf("`", "`", "``", "``"), byKind[MarkupKind.CODE])
        assertEquals(listOf("["), byKind[MarkupKind.LINK_OPEN])
        assertEquals(listOf("](https://x.y/z \"title\")"), byKind[MarkupKind.LINK_TAIL])
        assertEquals(listOf("[ ]"), byKind[MarkupKind.CHECKBOX_OFF])
        assertEquals(listOf("[x]", "[X]"), byKind[MarkupKind.CHECKBOX_ON])
        assertEquals(MarkupKind.entries.toSet(), byKind.keys)
    }

    fun testPlaceholdersAreEmptyExceptForCheckboxes() {
        for (range in collectFixture()) {
            val expected = when (range.kind) {
                MarkupKind.CHECKBOX_OFF -> "☐"
                MarkupKind.CHECKBOX_ON -> "☑"
                else -> ""
            }
            assertEquals(range.toString(), expected, range.placeholder)
        }
    }

    fun testCodeFencesBlocksHtmlAndImagesStayRaw() {
        val ranges = collectFixture()
        val text = myFixture.file.text
        for (raw in listOf("**not** `here`", "    **indented** code", "<div>**html**</div>", "![img](pic.png)", "<https://auto.link>")) {
            val start = text.indexOf(raw)
            assertTrue(raw, start >= 0)
            val end = start + raw.length
            assertEmpty("inside $raw", ranges.filter { it.range.startOffset < end && it.range.endOffset > start })
        }
    }

    fun testNestedMarkersInsideLinkTextAreCollected() {
        val text = myFixture.configureByText("a.md", "[see **this** one](x.md)").text
        assertEquals("see this one", render(text, MarkupRangeCollector.collect(myFixture.file)))
    }

    fun testEmptyHeadingsAndEmptyLinkTextStayRaw() {
        val text = myFixture.configureByText("a.md", "#\n# \n[](x.md)\n").text
        assertEmpty(MarkupRangeCollector.collect(myFixture.file))
        assertEquals("#\n# \n[](x.md)\n", text)
    }

    fun testPlainTextYieldsNothing() {
        myFixture.configureByText("a.md", "just words, 2 * 3 = 6, a_b_c\n")
        assertEmpty(MarkupRangeCollector.collect(myFixture.file))
        myFixture.configureByText("b.md", "")
        assertEmpty(MarkupRangeCollector.collect(myFixture.file))
    }

    private fun collectFixture(): List<MarkupRange> {
        myFixture.configureByFile("collector.md")
        return MarkupRangeCollector.collect(myFixture.file)
    }

    private fun render(text: String, ranges: List<MarkupRange>): String {
        val sb = StringBuilder(text)
        for (range in ranges.sortedByDescending { it.range.startOffset }) sb.replace(range.range.startOffset, range.range.endOffset, range.placeholder)
        return sb.toString()
    }
}
