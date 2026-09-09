package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Steps H1.2 and H3.2: one full-width background per block, owned by the controller's sync — created with the
 * fold regions, following edits, and gone when live markup is switched off or the option is. A block quote
 * takes the same path and adds the accent bar down the column its `>` markers left behind.
 */
class MarkdownBlockRendererTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData/markdown"

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testOneHighlighterPerBlockCoveringTheWholeBlock() {
        myFixture.configureByFile("fences.md")
        val controller = attachedController()
        controller.syncNow()

        val highlighters = controller.blockHighlighters()
        val blocks = MarkupRangeCollector.collectMarkup(myFixture.file).blocks
        assertEquals("seven fences and the quote that holds one of them", 8, blocks.size)
        assertEquals(blocks.size, highlighters.size)
        assertEquals(
            blocks.map { it.span.startOffset to it.span.endOffset },
            highlighters.map { it.startOffset to it.endOffset },
        )
    }

    fun testAQuoteGetsACardAndABarWhileAFenceGetsOnlyACard() {
        myFixture.configureByText("a.md", "> quoted\n> lines\n\n```php\necho 1;\n```\n")
        val controller = attachedController()
        controller.syncNow()

        val (quote, fence) = controller.blockHighlighters().sortedBy { it.startOffset }
        assertNotNull("a quote is drawn with a bar of its own", quote.customRenderer)
        assertNull("a fence is the card and nothing else", fence.customRenderer)
        assertNotNull(quote.getTextAttributes(myFixture.editor.colorsScheme)?.backgroundColor)
    }

    fun testTheBarSpansEveryLineOfTheBlock() {
        val lineHeight = 20

        // One line: the bar is exactly that line tall. Three: from the first line's top to the last one's bottom.
        assertEquals(lineHeight, MarkdownBlockRenderer.barBounds(0, 0, lineHeight, x = 0).height)
        assertEquals(3 * lineHeight, MarkdownBlockRenderer.barBounds(0, 2 * lineHeight, lineHeight, x = 0).height)

        val bounds = MarkdownBlockRenderer.barBounds(40, 80, lineHeight, x = 3)
        assertEquals(3, bounds.x)
        assertEquals(40, bounds.y)
        assertTrue("the bar is a hairline, not a block", bounds.width in 1..8)
    }

    fun testTheQuoteCardIsNotTheFenceCardWhenTheSchemeSaysSo() {
        myFixture.configureByText("a.md", "> quoted\n")
        val scheme = myFixture.editor.colorsScheme

        // Whatever the scheme defines, each kind is asked for its own key rather than sharing one.
        val quote = MarkdownBlockRenderer.background(scheme, MarkdownBlockKind.BLOCK_QUOTE)
        val fence = MarkdownBlockRenderer.background(scheme, MarkdownBlockKind.CODE_FENCE)
        assertNotNull(quote)
        assertNotNull(fence)
    }

    fun testTheQuoteCardGoesWhenTheOptionIsOff() {
        myFixture.configureByText("a.md", "> quoted\n")
        val controller = attachedController()
        controller.syncNow()
        assertSize(1, controller.blockHighlighters())

        AgenstormSettings.getInstance().state.liveMarkupBlockQuotes = false
        controller.syncNow()

        assertEmpty(controller.blockHighlighters())
    }

    fun testTheBackgroundReachesPastTheTextToTheRightEdge() {
        myFixture.configureByText("a.md", "```php\necho 1;\n```\n")
        val controller = attachedController()
        controller.syncNow()

        val highlighter = controller.blockHighlighters().single()
        // EXACT_RANGE would stop under the text: IterationState ignores it past the end of a line.
        assertEquals(HighlighterTargetArea.LINES_IN_RANGE, highlighter.targetArea)
        assertNotNull("the card needs a background even in a scheme without CODE_FENCE", highlighter.getTextAttributes(myFixture.editor.colorsScheme)?.backgroundColor)
    }

    fun testASecondSyncKeepsTheSameHighlighter() {
        myFixture.configureByText("a.md", "```php\necho 1;\n```\n")
        val controller = attachedController()
        controller.syncNow()
        val first = controller.blockHighlighters().single()

        controller.syncNow()

        assertSame(first, controller.blockHighlighters().single())
    }

    fun testAnEditInsideTheFenceMovesTheBackgroundWithIt() {
        myFixture.configureByText("a.md", "```php\necho 1;\n```\n")
        val controller = attachedController()
        controller.syncNow()
        val before = controller.blockHighlighters().single().endOffset

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(myFixture.editor.document.text.indexOf("echo 1;"), "echo 0;\n")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        controller.syncNow()

        val highlighter = controller.blockHighlighters().single()
        assertEquals(before + "echo 0;\n".length, highlighter.endOffset)
        assertEquals(0, highlighter.startOffset)
    }

    fun testRemovingATextAroundTheFenceDropsTheBackground() {
        myFixture.configureByText("a.md", "```php\necho 1;\n```\n")
        val controller = attachedController()
        controller.syncNow()

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText("no fence here\n")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        controller.syncNow()

        assertEmpty(controller.blockHighlighters())
    }

    fun testTurningLiveMarkupOffLeavesNoBackground() {
        myFixture.configureByText("a.md", "```php\necho 1;\n```\n")
        val controller = attachedController()
        controller.syncNow()
        assertSize(1, controller.blockHighlighters())

        controller.removeAll()

        assertEmpty(controller.blockHighlighters())
    }

    fun testNoBackgroundWhenTheCodeBlockOptionIsOff() {
        AgenstormSettings.getInstance().state.liveMarkupCodeBlocks = false
        myFixture.configureByText("a.md", "```php\necho 1;\n```\n")
        val controller = attachedController()

        controller.syncNow()

        assertEmpty(controller.blockHighlighters())
        assertEmpty(controller.regions())
    }

    private fun attachedController(): LiveMarkupController {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return LiveMarkupService.getInstance(project).controllerFor(myFixture.editor)
            ?: error("no live markup controller on the Markdown editor")
    }
}
