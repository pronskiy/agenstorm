package com.pronskiy.agenstorm.markdown

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.editor.CaretState
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.FoldingListener
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Steps F1.2 / F1.3 / F1.5: the listener attaches a controller to Markdown editors only, the controller mirrors the
 * collector into light fold regions (collapsed except on caret lines and under selections), follows edits by keeping
 * what still fits, leaves the Markdown plugin's own regions alone and survives its folding pass.
 */
class LiveMarkupControllerTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData/markdown"

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testMarkdownEditorGetsOneCollapsedRegionPerMarker() {
        myFixture.configureByFile("collector.md")
        caretToEnd()
        val controller = attachedController()
        controller.syncNow()

        val wanted = MarkupRangeCollector.collect(myFixture.file)
        assertTrue(wanted.size > 10)
        val ours = controller.regions()
        assertEquals(wanted.map { it.range to it.placeholder }, ours.map { TextRange(it.startOffset, it.endOffset) to it.placeholderText })
        for (region in ours) {
            assertFalse(region.toString(), region.isExpanded)
            assertFalse(region.isGutterMarkEnabledForSingleLine)
            assertEquals(wanted.first { it.range.startOffset == region.startOffset }.kind, region.getUserData(LiveMarkupController.KIND))
        }
    }

    fun testEditsAddRemoveAndKeepRegions() {
        myFixture.configureByText("a.md", "plain **bold**\n")
        val controller = attachedController()
        controller.syncNow()
        assertEquals(listOf("**", "**"), texts(controller.regions()))
        val boldRegions = controller.regions()

        type("and *em*\n")
        controller.syncNow()
        assertEquals(listOf("**", "**", "*", "*"), texts(controller.regions()))
        assertEquals("the bold regions are kept, not recreated", boldRegions, controller.regions().take(2))

        replaceAll("plain bold\nand *em*\n")
        controller.syncNow()
        assertEquals(listOf("*", "*"), texts(controller.regions()))
    }

    fun testCheckboxPlaceholderFollowsTheDocument() {
        myFixture.configureByText("a.md", "- [ ] task\n")
        val controller = attachedController()
        controller.syncNow()
        assertEquals(listOf("☐"), controller.regions().map { it.placeholderText })

        replaceAll("- [x] task\n")
        controller.syncNow()
        assertEquals(listOf("☑"), controller.regions().map { it.placeholderText })
        assertEquals(listOf("[x]"), texts(controller.regions()))
    }

    fun testOtherFileTypesAndDisabledFeatureGetNoController() {
        myFixture.configureByText("a.txt", "**bold**\n")
        assertNull(LiveMarkupService.getInstance(project).controllerFor(myFixture.editor))

        AgenstormSettings.getInstance().loadState(AgenstormSettings.State(liveMarkupEnabled = false))
        myFixture.configureByText("b.md", "**bold**\n")
        assertNull(LiveMarkupService.getInstance(project).controllerFor(myFixture.editor))
        assertFalse(LiveMarkupService.getInstance(project).isEligible(myFixture.editor))
    }

    fun testMarkdownPluginRegionsSurviveRemoveAllAndOursSurviveItsFoldingPass() {
        myFixture.configureByFile("collector.md")
        caretToEnd()
        val controller = attachedController()
        controller.syncNow()
        val ours = controller.regions()

        // The Markdown plugin's folding pass (headings, lists, fences, tables); buildInitialFoldings is a no-op here.
        CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)
        val foreign = foreignRegions()
        assertTrue("the Markdown plugin folds headings and lists in this fixture", foreign.isNotEmpty())
        assertEquals("light regions survive UpdateFoldRegionsOperation", ours, controller.regions())
        myFixture.doHighlighting()
        assertEquals("light regions survive the highlighting passes", ours, controller.regions())
        assertTrue(controller.regions().all { it.isValid && !it.isExpanded })

        controller.removeAll()
        assertEmpty(controller.regions())
        assertEquals(foreign, foreignRegions())
    }

    fun testCaretLineIsRevealedAndFollowsTheCaret() {
        myFixture.configureByText("a.md", "**a**\n*b*\n~~c~~\n")
        val controller = attachedController()
        controller.syncNow()
        assertEquals(mapOf(0 to true, 1 to false, 2 to false), expandedByLine(controller))

        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.getLineStartOffset(1) + 1)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(mapOf(0 to false, 1 to true, 2 to false), expandedByLine(controller))

        controller.syncNow()
        assertEquals("a sync keeps the caret line open", mapOf(0 to false, 1 to true, 2 to false), expandedByLine(controller))
    }

    fun testSelectionRevealsIntersectingRegionsOnly() {
        myFixture.configureByText("a.md", "**a**\n*b*\n~~c~~\n")
        val controller = attachedController()
        val document = myFixture.editor.document
        controller.syncNow()

        // From inside the first line's closing marker to the middle of the last line: the opening ** of line 0 is
        // neither on the caret line nor under the selection and stays hidden; everything else is revealed.
        val start = 4
        val end = document.getLineStartOffset(2) + 3
        myFixture.editor.caretModel.moveToOffset(end)
        myFixture.editor.selectionModel.setSelection(start, end)
        UIUtil.dispatchAllInvocationEvents()
        val byRange = controller.regions().associate { texts(listOf(it)).single() + "@" + it.startOffset to it.isExpanded }
        assertEquals(mapOf("**@0" to false, "**@3" to true, "*@6" to true, "*@8" to true, "~~@10" to true, "~~@13" to true), byRange)

        myFixture.editor.selectionModel.removeSelection()
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(mapOf(0 to false, 1 to false, 2 to true), expandedByLine(controller))
    }

    fun testEveryCaretLineIsRevealed() {
        myFixture.configureByText("a.md", "**a**\n*b*\n~~c~~\n")
        val controller = attachedController()
        controller.syncNow()

        myFixture.editor.caretModel.setCaretsAndSelections(listOf(CaretState(LogicalPosition(0, 0), null, null), CaretState(LogicalPosition(2, 0), null, null)))
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(mapOf(0 to true, 1 to false, 2 to true), expandedByLine(controller))

        myFixture.editor.caretModel.setCaretsAndSelections(listOf(CaretState(LogicalPosition(1, 0), null, null)))
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(mapOf(0 to false, 1 to true, 2 to false), expandedByLine(controller))
    }

    fun testMovingWithinTheLineCostsNoFoldOperation() {
        myFixture.configureByText("a.md", "**a** and *b*\n~~c~~\n")
        val controller = attachedController()
        controller.syncNow()
        var batches = 0
        (myFixture.editor as EditorEx).foldingModel.addListener(object : FoldingListener {
            override fun onFoldProcessingEnd() {
                batches++
            }
        }, testRootDisposable)

        for (offset in 1..6) {
            myFixture.editor.caretModel.moveToOffset(offset)
            UIUtil.dispatchAllInvocationEvents()
        }
        assertEquals(0, batches)

        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.getLineStartOffset(1))
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(1, batches)
        assertEquals(mapOf(0 to false, 1 to true), expandedByLine(controller))
    }

    fun testDetachRemovesEveryRegion() {
        myFixture.configureByText("a.md", "# h\n**b** `c`\n")
        val controller = attachedController()
        controller.syncNow()
        assertEquals(5, controller.regions().size)

        LiveMarkupService.getInstance(project).detach(myFixture.editor)
        assertEmpty(myFixture.editor.foldingModel.allFoldRegions.filter { it.getUserData(LiveMarkupController.KIND) != null })
        assertNull(LiveMarkupService.getInstance(project).controllerFor(myFixture.editor))
    }

    private fun caretToEnd() = myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.textLength)

    /** Line → whether the regions on it are expanded; a line with mixed states fails the test. */
    private fun expandedByLine(controller: LiveMarkupController): Map<Int, Boolean> {
        val document = myFixture.editor.document
        return controller.regions().groupBy { document.getLineNumber(it.startOffset) }.mapValues { (line, regions) ->
            regions.map { it.isExpanded }.distinct().singleOrNull() ?: error("mixed states on line $line")
        }
    }

    private fun attachedController(): LiveMarkupController {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return LiveMarkupService.getInstance(project).controllerFor(myFixture.editor) ?: error("no live markup controller on the Markdown editor")
    }

    private fun foreignRegions(): List<FoldRegion> = myFixture.editor.foldingModel.allFoldRegions.filter { it.getUserData(LiveMarkupController.KIND) == null }

    private fun texts(regions: List<FoldRegion>): List<String> = regions.map { myFixture.editor.document.getText(TextRange(it.startOffset, it.endOffset)) }

    private fun type(text: String) {
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, text) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun replaceAll(text: String) {
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }
}
