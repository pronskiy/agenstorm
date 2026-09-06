package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step H1.2: one full-width background per fence, owned by the controller's sync — created with the fold
 * regions, following edits, and gone when live markup is switched off or the option is.
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

    fun testOneHighlighterPerFenceCoveringTheWholeBlock() {
        myFixture.configureByFile("fences.md")
        val controller = attachedController()
        controller.syncNow()

        val highlighters = controller.blockHighlighters()
        val blocks = MarkupRangeCollector.collectMarkup(myFixture.file).blocks
        assertEquals(7, blocks.size)
        assertEquals(blocks.size, highlighters.size)
        assertEquals(
            blocks.map { it.span.startOffset to it.span.endOffset },
            highlighters.map { it.startOffset to it.endOffset },
        )
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
