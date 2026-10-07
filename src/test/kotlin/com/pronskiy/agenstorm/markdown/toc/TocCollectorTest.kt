package com.pronskiy.agenstorm.markdown.toc

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Step W1.1: the headings of `testData/markdown/toc.md` as a reader sees them, and the front matter rule. */
class TocCollectorTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData/markdown"

    fun testHeadingsComeInOrderAsPlainText() {
        myFixture.configureByFile("toc.md")
        val entries = TocCollector.collect(myFixture.file)
        assertEquals(
            listOf(
                1 to "Agenstorm",
                2 to "Install the CLI",
                3 to "Use open and friends",
                1 to "Setext one",
                2 to "Setext two",
                4 to "Deep old new",
                5 to "Links and",
                6 to "Six",
            ),
            entries.map { it.level to it.title },
        )
    }

    fun testLinesAndOffsetsPointAtTheHeadingStart() {
        val text = myFixture.configureByFile("toc.md").text
        val entries = TocCollector.collect(myFixture.file)
        val agenstorm = entries.first()
        assertEquals(text.indexOf("# Agenstorm"), agenstorm.offset)
        assertEquals(4, agenstorm.line)
        val setext = entries.single { it.title == "Setext one" }
        assertEquals(text.indexOf("Setext one"), setext.offset)
    }

    fun testImageOnlyAndEmptyHeadingsAreSkipped() {
        myFixture.configureByText("a.md", "#\n\n# ![only](image.png)\n\n# Real\n")
        assertEquals(listOf("Real"), TocCollector.collect(myFixture.file).map { it.title })
    }

    fun testInlineHtmlIsLeftOutAndAutolinksLoseTheirBrackets() {
        myFixture.configureByText(
            "a.md",
            "# <img src=\"logo.svg\" width=\"32\"> Agenstorm\n\n# <img src=\"logo.svg\">\n\n## See <https://x.dev>\n\n## <b>Bold</b> text\n",
        )
        assertEquals(listOf("Agenstorm", "See https://x.dev", "Bold text"), TocCollector.collect(myFixture.file).map { it.title })
    }

    fun testFrontMatterEndsAtItsClosingLine() {
        assertEquals(0, TocCollector.frontMatterEnd("# Title\n"))
        assertEquals("---\na: b\n---".length, TocCollector.frontMatterEnd("---\na: b\n---\n# Title\n"))
        assertEquals("---\r\na: b\r\n...".length, TocCollector.frontMatterEnd("---\r\na: b\r\n...\r\n"))
        assertEquals("unclosed is no front matter", 0, TocCollector.frontMatterEnd("---\na: b\n# Title\n"))
        assertEquals("a rule later in the file is not front matter", 0, TocCollector.frontMatterEnd("Text\n---\n"))
    }
}
