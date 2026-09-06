package com.pronskiy.agenstorm.markdown

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.psi.codeStyle.CodeStyleManager
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
 * Steps F1.2 / F1.3 / F1.4 / F1.5 / F2.2 / F3.1: the listener attaches a controller to Markdown editors only, the controller mirrors the
 * collector into light fold regions (collapsed except for the element at a caret, the line of a block marker, and
 * under selections), follows edits by keeping what still fits, leaves the Markdown plugin's own regions alone and
 * survives its folding pass.
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
        assertEquals(listOf("•", "☐"), controller.regions().map { it.placeholderText })

        replaceAll("- [x] task\n")
        controller.syncNow()
        assertEquals(listOf("•", "☑"), controller.regions().map { it.placeholderText })
        assertEquals(listOf("-", "[x]"), texts(controller.regions()))
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

    fun testSelectionRevealsOverlappingElementsOnly() {
        myFixture.configureByText("a.md", "**a**\n*b*\n~~c~~\n")
        val controller = attachedController()
        val document = myFixture.editor.document
        controller.syncNow()

        // From the end of the first element (touching, not overlapping) to the middle of the last line: `**a**` stays
        // hidden, `*b*` is under the selection, `~~c~~` holds the caret.
        val start = 5
        val end = document.getLineStartOffset(2) + 3
        myFixture.editor.caretModel.moveToOffset(end)
        myFixture.editor.selectionModel.setSelection(start, end)
        UIUtil.dispatchAllInvocationEvents()
        val byRange = controller.regions().associate { texts(listOf(it)).single() + "@" + it.startOffset to it.isExpanded }
        assertEquals(mapOf("**@0" to false, "**@3" to false, "*@6" to true, "*@8" to true, "~~@10" to true, "~~@13" to true), byRange)

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

    fun testMovingWithinAnElementCostsNoFoldOperation() {
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
        assertEquals("inside, touching or one step past `**a**` all along", 0, batches)

        myFixture.editor.caretModel.moveToOffset(7)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("leaving the element hides it", 1, batches)
        assertEquals(mapOf(0 to false), expandedByLine(controller).filterKeys { it == 0 })

        myFixture.editor.caretModel.moveToOffset(9)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("approaching `*b*` reveals it", 2, batches)

        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.getLineStartOffset(1))
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("one batch hides `*b*` and reveals `~~c~~`", 3, batches)
        assertEquals(mapOf(0 to false, 1 to true), expandedByLine(controller))
    }

    fun testOnlyTheElementAtTheCaretIsRevealedOnItsLine() {
        myFixture.configureByText("a.md", "**a** and *b* and [c](x.md)\n")
        val controller = attachedController()
        controller.syncNow()
        assertEquals(listOf(true, true, false, false, false, false), controller.regions().map { it.isExpanded })

        myFixture.editor.caretModel.moveToOffset(11)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("only *b*", listOf(false, false, true, true, false, false), controller.regions().map { it.isExpanded })

        myFixture.editor.caretModel.moveToOffset(19)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("only the link, both of its markers", listOf(false, false, false, false, true, true), controller.regions().map { it.isExpanded })
    }

    fun testApproachingEitherEndRevealsAndLeavingHides() {
        myFixture.configureByText("a.md", "xx **b** yy\n")
        val controller = attachedController()
        // The element spans 3..8; it opens one character early on each side so arrow keys never skip a marker.
        for ((offset, expected) in listOf(1 to false, 2 to true, 3 to true, 5 to true, 8 to true, 9 to true, 10 to false)) {
            myFixture.editor.caretModel.moveToOffset(offset)
            UIUtil.dispatchAllInvocationEvents()
            if (offset == 1) controller.syncNow()
            assertEquals("caret at $offset", listOf(expected, expected), controller.regions().map { it.isExpanded })
        }
    }

    fun testNestedAndContainedElementsRevealTogether() {
        myFixture.configureByText("a.md", "***both*** [**b**](x.md)\n")
        val controller = attachedController()
        caretToEnd()
        controller.syncNow()
        fun revealed() = controller.regions().filter { it.isExpanded }.map { texts(listOf(it)).single() + "@" + it.startOffset }

        myFixture.editor.caretModel.moveToOffset(5)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("inside both: emphasis and strong", listOf("*@0", "**@1", "**@7", "*@9"), revealed())

        myFixture.editor.caretModel.moveToOffset(0)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("touching the outer: the inner is one step away and opens early too", listOf("*@0", "**@1", "**@7", "*@9"), revealed())

        caretToEnd()
        UIUtil.dispatchAllInvocationEvents()
        assertEmpty(revealed())

        myFixture.editor.caretModel.moveToOffset(14)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("bold inside link text reveals the link too", listOf("[@11", "**@12", "**@15", "](x.md)@17"), revealed())
    }

    fun testASelectionAcrossTwoElementsRevealsBoth() {
        myFixture.configureByText("a.md", "**a** and *b* and ~~c~~\n")
        val controller = attachedController()
        caretToEnd()
        controller.syncNow()
        myFixture.editor.caretModel.moveToOffset(3)
        myFixture.editor.selectionModel.setSelection(3, 11)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf(true, true, true, true, false, false), controller.regions().map { it.isExpanded })
    }

    fun testArrowKeysNeverSkipAMarker() {
        myFixture.configureByText("a.md", "x **b** y\n")
        val controller = attachedController()
        controller.syncNow()
        val forward = ArrayList<Int>()
        repeat(9) {
            runEditorAction("EditorRight")
            UIUtil.dispatchAllInvocationEvents()
            forward += myFixture.editor.caretModel.offset
        }
        assertEquals((1..9).toList(), forward)
        val back = ArrayList<Int>()
        repeat(9) {
            runEditorAction("EditorLeft")
            UIUtil.dispatchAllInvocationEvents()
            back += myFixture.editor.caretModel.offset
        }
        assertEquals((8 downTo 0).toList(), back)
    }

    fun testExpandAllAndCollapseAllAreFollowedByTheCaretPolicy() {
        myFixture.configureByFile("collector.md")
        caretToEnd()
        val controller = attachedController()
        CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)
        controller.syncNow()
        val ours = controller.regions()
        val foreign = foreignRegions()
        assertTrue(foreign.isNotEmpty())
        assertTrue(ours.none { it.isExpanded })

        runEditorAction("ExpandAllRegions")
        assertTrue("observed: Expand All expands our regions too", controller.regions().all { it.isExpanded })
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("same regions afterwards", ours, controller.regions())
        assertTrue("hidden again once the policy ran", controller.regions().none { it.isExpanded })
        assertTrue("the Markdown plugin's regions stay as Expand All left them", foreignRegions().all { it.isExpanded })

        myFixture.editor.caretModel.moveToOffset(3)
        UIUtil.dispatchAllInvocationEvents()
        val heading = controller.regions().first { it.startOffset == 0 }
        assertTrue(heading.isExpanded)
        runEditorAction("CollapseAllRegions")
        assertFalse("observed: Collapse All collapses the caret line's regions", heading.isExpanded)
        UIUtil.dispatchAllInvocationEvents()
        assertTrue(heading.isValid && heading.isExpanded)
        assertEquals(mapOf(0 to true), expandedByLine(controller).filterValues { it })
        assertEquals(foreign, foreignRegions())
    }

    fun testTypingAtBordersReformatAndUndoStayConsistent() {
        myFixture.configureByText("a.md", "**bold** and *em*\n\n**far**\n")
        val controller = attachedController()
        val document = myFixture.editor.document
        controller.syncNow()
        assertConsistent(controller)

        myFixture.editor.caretModel.moveToOffset(2)
        myFixture.type("x")
        commitAndSync(controller)
        assertEquals("**xbold** and *em*\n\n**far**\n", document.text)
        assertConsistent(controller)

        myFixture.editor.caretModel.moveToOffset(0)
        myFixture.type("y")
        commitAndSync(controller)
        assertEquals("y**xbold** and *em*\n\n**far**\n", document.text)
        assertConsistent(controller)

        // An outside edit at the border of a collapsed region on another line (an agent rewriting the file).
        val far = controller.regions().first { document.getLineNumber(it.startOffset) == 2 }
        assertFalse(far.isExpanded)
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(far.endOffset, "z") }
        commitAndSync(controller)
        assertEquals("y**xbold** and *em*\n\n**zfar**\n", document.text)
        assertConsistent(controller)
        assertFalse(controller.regions().first { document.getLineNumber(it.startOffset) == 2 }.isExpanded)

        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
        commitAndSync(controller)
        assertConsistent(controller)

        val fileEditor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undoManager = UndoManager.getInstance(project)
        assertTrue(undoManager.isUndoAvailable(fileEditor))
        undoManager.undo(fileEditor)
        undoManager.undo(fileEditor)
        commitAndSync(controller)
        assertEquals("y**xbold** and *em*\n\n**far**\n", document.text)
        assertConsistent(controller)
    }

    fun testClickingACheckboxFlipsItWithoutMovingTheCaretOrLosingTheRegion() {
        myFixture.configureByText("a.md", "- [ ] one\n- [x] two\n\n**b**\n")
        val document = myFixture.editor.document
        caretToEnd()
        val controller = attachedController()
        controller.syncNow()
        val caretBefore = myFixture.editor.caretModel.offset
        val first = controller.regions().first { it.getUserData(LiveMarkupController.KIND) == MarkupKind.CHECKBOX_OFF }
        val second = controller.regions().first { it.getUserData(LiveMarkupController.KIND) == MarkupKind.CHECKBOX_ON }

        assertTrue(controller.toggleCheckbox(first))
        assertEquals("- [x] one\n- [x] two\n\n**b**\n", document.text)
        assertTrue("the region is kept, not recreated", first.isValid)
        assertEquals("☑", first.placeholderText)
        assertEquals(MarkupKind.CHECKBOX_ON, first.getUserData(LiveMarkupController.KIND))
        assertFalse(first.isExpanded)
        assertEquals(caretBefore, myFixture.editor.caretModel.offset)

        assertTrue(controller.toggleCheckbox(second))
        assertEquals("- [x] one\n- [ ] two\n\n**b**\n", document.text)
        assertEquals("☐", second.placeholderText)

        // The debounced sync agrees with the swapped placeholders: same region objects survive.
        commitAndSync(controller)
        assertTrue(first.isValid && second.isValid)
        assertEquals(listOf("-", "[x]", "-", "[ ]", "**", "**"), texts(controller.regions()))

        val bold = controller.regions().first { it.getUserData(LiveMarkupController.KIND) == MarkupKind.STRONG }
        assertFalse("only checkbox regions toggle", controller.toggleCheckbox(bold))
        assertEquals("- [x] one\n- [ ] two\n\n**b**\n", document.text)
    }

    fun testCheckboxToggleIsOneUndoStep() {
        myFixture.configureByText("a.md", "- [ ] one\n")
        caretToEnd()
        val controller = attachedController()
        controller.syncNow()
        val region = controller.regions().single { it.getUserData(LiveMarkupController.KIND) == MarkupKind.CHECKBOX_OFF }
        assertTrue(controller.toggleCheckbox(region))
        assertEquals("- [x] one\n", myFixture.editor.document.text)

        val fileEditor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        UndoManager.getInstance(project).undo(fileEditor)
        assertEquals("- [ ] one\n", myFixture.editor.document.text)
        commitAndSync(controller)
        assertEquals(listOf("•", "☐"), controller.regions().map { it.placeholderText })
        assertEquals(listOf(MarkupKind.BULLET, MarkupKind.CHECKBOX_OFF), controller.regions().map { it.getUserData(LiveMarkupController.KIND) })
    }

    fun testRegionsRestoredFromAPreviousSessionAreReplacedAndOpenOnTheCaretLine() {
        myFixture.configureByText("a.md", "**a**\n*b*\n~~c~~\n")
        caretToEnd()
        val service = LiveMarkupService.getInstance(project)
        val first = attachedController()
        first.syncNow()
        assertEquals(6, first.regions().size)

        // Closing the editor persists every collapsed region as a plain range + placeholder; reopening restores them.
        // (The state is only saved once the folding pass has initialised the editor, which highlighting does.)
        myFixture.doHighlighting()
        val foldingManager = CodeFoldingManager.getInstance(project)
        val state = foldingManager.saveFoldingState(myFixture.editor)
        service.detach(myFixture.editor)
        assertEmpty(orphans())
        val paragraphFold = foreignRegions().single()
        // The text editor restores its state inside a batch operation; the manager relies on that.
        myFixture.editor.foldingModel.runBatchFoldingOperation { foldingManager.restoreFoldingState(myFixture.editor, state) }
        val orphans = orphans()
        assertEquals("the platform brings the collapsed regions back without our marker", 6, orphans.size)
        assertTrue(orphans.none { it.isExpanded })

        val again = service.attach(myFixture.editor)!!
        again.syncNow()
        assertEquals(6, again.regions().size)
        assertEmpty("orphans on our ranges are replaced", orphans())
        assertEquals("the Markdown plugin's own region is untouched", listOf(paragraphFold), foreignRegions())

        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.getLineStartOffset(1))
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(mapOf(0 to false, 1 to true, 2 to false), expandedByLine(again))
    }

    fun testAStateRestoredOverOurRegionsIsCorrectedByThePolicy() {
        myFixture.configureByText("a.md", "**a**\n*b*\n")
        caretToEnd()
        val controller = attachedController()
        controller.syncNow()
        myFixture.doHighlighting()
        val foldingManager = CodeFoldingManager.getInstance(project)
        val state = foldingManager.saveFoldingState(myFixture.editor)

        myFixture.editor.caretModel.moveToOffset(0)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(mapOf(0 to true, 1 to false), expandedByLine(controller))

        // The text editor restores its state inside a batch operation; the manager relies on that.
        myFixture.editor.foldingModel.runBatchFoldingOperation { foldingManager.restoreFoldingState(myFixture.editor, state) }
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(4, controller.regions().size)
        assertEmpty(orphans())
        assertEquals("the caret line is open again after the foreign batch", mapOf(0 to true, 1 to false), expandedByLine(controller))
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

    private fun runEditorAction(id: String) {
        EditorActionManager.getInstance().getActionHandler(id).execute(myFixture.editor, null, EditorUtil.getEditorDataContext(myFixture.editor))
    }

    private fun commitAndSync(controller: LiveMarkupController) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        controller.syncNow()
    }

    /** Regions equal the collector's output and every region's state follows the caret policy (judged from the collector's spans). */
    private fun assertConsistent(controller: LiveMarkupController) {
        val document = myFixture.editor.document
        val wanted = MarkupRangeCollector.collect(myFixture.file)
        assertEquals(wanted.map { it.range to it.placeholder }, controller.regions().map { TextRange(it.startOffset, it.endOffset) to it.placeholderText })
        val carets = LiveMarkupController.Carets.of(myFixture.editor as EditorEx)
        fun line(offset: Int) = document.getLineNumber(offset).let { TextRange(document.getLineStartOffset(it), document.getLineEndOffset(it)) }
        for ((range, region) in wanted.zip(controller.regions())) {
            val span = if (range.kind.isBlock) line(range.range.startOffset) else LiveMarkupController.approach(range.span, line(range.span.startOffset), line(range.span.endOffset))
            assertEquals("${texts(listOf(region))}@${region.startOffset}", LiveMarkupController.isRevealed(span, carets), region.isExpanded)
        }
    }

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

    /** Foreign regions sitting exactly on a range the collector wants: leftovers of a persisted state. */
    private fun orphans(): List<FoldRegion> {
        val wanted = MarkupRangeCollector.collect(myFixture.file).map { it.range }.toSet()
        return foreignRegions().filter { TextRange(it.startOffset, it.endOffset) in wanted }
    }

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
