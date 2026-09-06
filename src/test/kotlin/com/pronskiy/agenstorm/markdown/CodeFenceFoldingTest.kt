package com.pronskiy.agenstorm.markdown

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

/**
 * Step H1.3: our two fence regions live inside the Markdown plugin's own whole-fence region without either
 * disturbing the other, the caret policy brings the markers back a line at a time, and the card's background
 * is there the whole time — revealed markers must not make it flicker away.
 *
 * The document is `` ```php ``(0..6) · `echo 1;`(7..14) · `` ``` ``(15..18): `FENCE_OPEN` is (0,6) and
 * `FENCE_CLOSE` is (14,18), the line break before the closing fence included.
 */
class CodeFenceFoldingTest : BasePlatformTestCase() {

    private val fence = "```php\necho 1;\n```\n"

    fun testOurRegionsAndTheMarkdownPluginsFenceRegionCoexist() {
        val controller = configured()
        val ours = controller.regions()
        CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)

        val whole = foreign().singleOrNull { it.startOffset == 0 && it.endOffset >= 18 }
        assertNotNull("the Markdown plugin folds the whole fence", whole)
        assertEquals("our regions survive its pass", ours.map { it.textRange }, controller.regions().map { it.textRange })
        for (region in controller.regions()) {
            assertTrue(
                "$region must nest strictly inside ${whole!!.textRange}",
                region.startOffset >= whole.startOffset && region.endOffset <= whole.endOffset,
            )
        }
        assertSize(1, controller.blockHighlighters())
    }

    fun testCollapsingTheWholeFenceWithTheMarkdownPluginLeavesOursAlone() {
        val controller = configured()
        CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)
        val whole = foreign().single { it.startOffset == 0 && it.endOffset >= 18 }

        myFixture.editor.foldingModel.runBatchFoldingOperation { whole.isExpanded = false }
        UIUtil.dispatchAllInvocationEvents()

        assertFalse("the plugin's own fence folding still works", whole.isExpanded)
        assertSize(2, controller.regions())
        assertSize(1, controller.blockHighlighters())
    }

    fun testTheCaretOnEitherFenceLineBringsBothMarkersBack() {
        val controller = configured()

        // Both markers share a FoldingGroup, which the platform expands as one, so either line reveals the pair.
        moveCaretTo(3)
        assertTrue("the header line reveals the fence", controller.regions().all { it.isExpanded })
        assertBackgroundIntact(controller)

        moveCaretTo(myFixture.editor.document.textLength)
        assertTrue(controller.regions().none { it.isExpanded })
        moveCaretTo(16)
        assertTrue("the closing line reveals it too", controller.regions().all { it.isExpanded })
        assertBackgroundIntact(controller)
    }

    fun testTheMiddleOfALongFenceRevealsNothing() {
        myFixture.configureByText("b.md", "```php\n" + (1..8).joinToString("\n") { "echo $it;" } + "\n```\n")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val controller = LiveMarkupService.getInstance(project).controllerFor(myFixture.editor)!!
        controller.syncNow()

        moveCaretTo(myFixture.editor.document.text.indexOf("echo 4;") + 2)

        assertTrue("neither fence line is near the caret", controller.regions().none { it.isExpanded })
        assertSize(1, controller.blockHighlighters())
    }

    fun testNeitherRegionDrawsAGutterArrow() {
        val controller = configured()
        val document = myFixture.editor.document

        for (region in controller.regions()) {
            assertEquals(
                "a region covering more than its own line always gets a gutter arrow: $region",
                document.getLineNumber(region.startOffset),
                document.getLineNumber(region.endOffset),
            )
            assertFalse(region.isGutterMarkEnabledForSingleLine)
        }
        assertEquals("every line keeps its number", 4, document.lineCount)
    }

    fun testAwayFromTheFenceEverythingHidesAgainAndTheCardStays() {
        val controller = configured()
        moveCaretTo(3)

        moveCaretTo(myFixture.editor.document.textLength)

        assertTrue(controller.regions().none { it.isExpanded })
        assertBackgroundIntact(controller)
    }

    fun testExpandAllIsFollowedByThePolicyAndTheCardSurvives() {
        val controller = configured()
        CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)
        moveCaretTo(myFixture.editor.document.textLength)

        EditorActionManager.getInstance().getActionHandler("ExpandAllRegions")
            .execute(myFixture.editor, null, EditorUtil.getEditorDataContext(myFixture.editor))
        assertTrue("Expand All expands ours too", controller.regions().all { it.isExpanded })
        UIUtil.dispatchAllInvocationEvents()

        assertTrue("the policy hides them again", controller.regions().none { it.isExpanded })
        assertBackgroundIntact(controller)
    }

    private fun configured(): LiveMarkupController {
        myFixture.configureByText("a.md", fence)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val controller = LiveMarkupService.getInstance(project).controllerFor(myFixture.editor)
            ?: error("no live markup controller on the Markdown editor")
        controller.syncNow()
        assertEquals(listOf(0 to 6, 15 to 18), controller.regions().map { it.startOffset to it.endOffset })
        return controller
    }

    private fun moveCaretTo(offset: Int) {
        myFixture.editor.caretModel.moveToOffset(offset)
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun assertBackgroundIntact(controller: LiveMarkupController) {
        val highlighter = controller.blockHighlighters().single()
        assertEquals(0, highlighter.startOffset)
        assertEquals(18, highlighter.endOffset)
    }

    private fun foreign(): List<FoldRegion> =
        myFixture.editor.foldingModel.allFoldRegions.filter { it.getUserData(LiveMarkupController.KIND) == null }
}
