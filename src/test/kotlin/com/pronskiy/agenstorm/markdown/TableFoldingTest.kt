package com.pronskiy.agenstorm.markdown

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.editor.FoldRegion
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step Q2.2: the table's region takes the line break before it and is revealed only from the inside — a caret
 * strictly inside or at its end, or a selection over it — never by the caret on the line above; the Markdown
 * plugin's own table region nests inside ours and neither disturbs the other; a region restored from a previous
 * session on the table's range is replaced (decision 23); the option leaves no region behind when off.
 *
 * The document is `Above`(0..5) · blank line (its break at 6) · `| a | b |`(7..16) · `|---|---|`(17..26) ·
 * `| **c** | d |`(27..40) · blank · `Below`(42..47). The table region is (6,40); the two `**` inside are ours too.
 */
class TableFoldingTest : BasePlatformTestCase() {

    private val text = "Above\n\n| a | b |\n|---|---|\n| **c** | d |\n\nBelow\n"

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testTheRegionTakesThePrecedingBreakAndTheCellMarkupNestsInside() {
        val controller = configured()
        val table = tableRegion(controller)
        assertEquals(6 to 40, table.startOffset to table.endOffset)
        assertFalse(table.isExpanded)
        val inner = controller.regions().filter { it !== table }
        assertEquals(listOf(29 to 31, 32 to 34), inner.map { it.startOffset to it.endOffset })
        assertTrue(inner.none { it.isExpanded })
    }

    fun testTheMarkdownPluginsTableRegionNestsInsideOursAndOursStaysCollapsed() {
        val controller = configured()
        CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)
        UIUtil.dispatchAllInvocationEvents()

        val theirs = foreign().filter { it.startOffset >= 7 && it.endOffset <= 40 && it.endOffset - it.startOffset > 20 }
        assertSize(1, theirs)
        val table = tableRegion(controller)
        assertEquals(6 to 40, table.startOffset to table.endOffset)
        assertFalse("the folding pass leaves ours collapsed", table.isExpanded)
    }

    fun testACaretInsideRevealsAndLeavingHidesAgain() {
        val controller = configured()
        moveCaretTo(20)
        assertTrue(tableRegion(controller).isExpanded)
        moveCaretTo(44)
        assertFalse(tableRegion(controller).isExpanded)
    }

    fun testTheCaretOnTheLineAboveDoesNotReveal() {
        val controller = configured()
        moveCaretTo(6)
        assertFalse(tableRegion(controller).isExpanded)
        moveCaretTo(3)
        assertFalse(tableRegion(controller).isExpanded)
    }

    fun testTheCaretAtTheEndOfTheTableReveals() {
        val controller = configured()
        moveCaretTo(40)
        assertTrue(tableRegion(controller).isExpanded)
    }

    fun testASelectionOverTheTableReveals() {
        val controller = configured()
        myFixture.editor.selectionModel.setSelection(0, 44)
        UIUtil.dispatchAllInvocationEvents()
        assertTrue(tableRegion(controller).isExpanded)
    }

    fun testARegionRestoredFromAPreviousSessionIsReplaced() {
        val service = LiveMarkupService.getInstance(project)
        val first = configured()
        assertSize(3, first.regions())
        myFixture.doHighlighting()
        val foldingManager = CodeFoldingManager.getInstance(project)
        val state = foldingManager.saveFoldingState(myFixture.editor)
        service.detach(myFixture.editor)
        myFixture.editor.foldingModel.runBatchFoldingOperation { foldingManager.restoreFoldingState(myFixture.editor, state) }
        assertTrue("the table came back as a plain collapsed region", foreign().any { it.startOffset == 6 && it.endOffset == 40 && !it.isExpanded })

        val again = service.attach(myFixture.editor)!!
        again.syncNow()
        assertEquals(6 to 40, tableRegion(again).let { it.startOffset to it.endOffset })
        assertTrue("no orphan is left on the table's range", foreign().none { it.startOffset == 6 && it.endOffset == 40 })
    }

    fun testTheOptionOffLeavesNoTableRegion() {
        val controller = configured()
        AgenstormSettings.getInstance().state.liveMarkupTables = false
        controller.syncNow()
        assertTrue(controller.regions().none { it.getUserData(LiveMarkupController.KIND) == MarkupKind.TABLE })
        assertSize(2, controller.regions())
    }

    private fun configured(): LiveMarkupController {
        myFixture.configureByText("a.md", text)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val controller = LiveMarkupService.getInstance(project).controllerFor(myFixture.editor)
            ?: error("no live markup controller on the Markdown editor")
        controller.syncNow()
        return controller
    }

    private fun tableRegion(controller: LiveMarkupController): FoldRegion =
        controller.regions().single { it.getUserData(LiveMarkupController.KIND) == MarkupKind.TABLE }

    private fun moveCaretTo(offset: Int) {
        myFixture.editor.caretModel.moveToOffset(offset)
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun foreign(): List<FoldRegion> =
        myFixture.editor.foldingModel.allFoldRegions.filter { it.getUserData(LiveMarkupController.KIND) == null }
}
