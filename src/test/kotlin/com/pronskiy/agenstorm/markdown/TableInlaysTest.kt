package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.markdown.tables.TableInlayRenderer
import com.pronskiy.agenstorm.markdown.tables.TablePainter
import java.awt.image.BufferedImage

/**
 * Step Q2.3: a collapsed table carries one block inlay under the line above it, painted by [TableInlayRenderer];
 * the inlay goes while the table is revealed and returns when it collapses again, follows an edit to the table,
 * is never created with the option off, and leaves with the controller.
 *
 * Same document as [TableFoldingTest]: the table region is (6,40), a caret at 20 is inside it.
 */
class TableInlaysTest : BasePlatformTestCase() {

    private val text = "Above\n\n| a | b |\n|---|---|\n| **c** | d |\n\nBelow\n"

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testOneInlayUnderTheLineAboveACollapsedTable() {
        val controller = configured()
        val inlay = controller.tableInlays().single()
        assertEquals(6, inlay.offset)
        assertTrue(inlay.isValid)
        assertFalse(inlay.properties.isShownAbove)
        assertTrue(inlay.properties.isShownWhenFolded)
        assertEquals(listOf("a", "b"), inlay.renderer.model.header.cells.map { cell -> cell.runs.joinToString("") { it.text } })
        assertNotNull("shown while the table is collapsed", inlay.bounds)
    }

    fun testTheInlayGoesWhileTheTableIsRevealedAndComesBack() {
        val controller = configured()
        moveCaretTo(20)
        assertEmpty(controller.tableInlays())
        moveCaretTo(44)
        assertSize(1, controller.tableInlays())
    }

    fun testAForeignExpandIsUndoneByThePolicyAndTheInlayReturns() {
        val controller = configured()
        val table = controller.regions().single { it.getUserData(LiveMarkupController.KIND) == MarkupKind.TABLE }
        myFixture.editor.foldingModel.runBatchFoldingOperation { table.isExpanded = true }
        UIUtil.dispatchAllInvocationEvents()
        assertFalse(table.isExpanded)
        assertSize(1, controller.tableInlays())
    }

    fun testAnEditInsideTheTableUpdatesTheInlay() {
        val controller = configured()
        val before = controller.tableInlays().single()
        val heightBefore = before.renderer.calcHeightInPixels(before)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(40, "\n| e | f |") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        controller.syncNow()
        val after = controller.tableInlays().single()
        assertEquals(2, after.renderer.model.rows.size)
        assertEquals(3, after.renderer.geometry().rows.size)
        assertTrue(after.renderer.calcHeightInPixels(after) > heightBefore)
    }

    fun testNoInlayWhenTheOptionIsOff() {
        val controller = configured()
        AgenstormSettings.getInstance().state.liveMarkupTables = false
        controller.syncNow()
        assertEmpty(controller.tableInlays())
    }

    fun testTheRendererHasASizeAndPaintsSomething() {
        val controller = configured()
        val inlay: Inlay<TableInlayRenderer> = controller.tableInlays().single()
        val width = inlay.renderer.calcWidthInPixels(inlay)
        val height = inlay.renderer.calcHeightInPixels(inlay)
        assertTrue("width $width", width > 0)
        assertTrue("height $height", height > 0)
        val image = BufferedImage(width + 10, height + 10, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        TablePainter.paint(g, inlay.renderer.geometry(), 0, 0, inlay.renderer.measurer(), inlay.renderer.palette())
        g.dispose()
        var painted = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (image.getRGB(x, y) != java.awt.Color.WHITE.rgb) painted++
        assertTrue("something was drawn", painted > 0)
    }

    fun testDetachRemovesTheInlays() {
        val controller = configured()
        assertSize(1, controller.tableInlays())
        LiveMarkupService.getInstance(project).detach(myFixture.editor)
        val document = myFixture.editor.document
        assertTrue(myFixture.editor.inlayModel.getBlockElementsInRange(0, document.textLength).none { it.renderer is TableInlayRenderer })
    }

    fun testDetachDisposesTheInlaysOwnerThroughTheDisposerTree() {
        // Found in the sandbox at shutdown: the width listener registers the owner in the Disposer tree, so it has to
        // be disposed through the tree as the controller's child, not by a direct call, or the tree reports a leak.
        val controller = configured()
        val owner = controller.tableInlaysOwner()
        LiveMarkupService.getInstance(project).detach(myFixture.editor)
        assertTrue(Disposer.isDisposed(controller))
        assertTrue(Disposer.isDisposed(owner))
    }

    private fun configured(): LiveMarkupController {
        myFixture.configureByText("a.md", text)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val controller = LiveMarkupService.getInstance(project).controllerFor(myFixture.editor)
            ?: error("no live markup controller on the Markdown editor")
        controller.syncNow()
        return controller
    }

    private fun moveCaretTo(offset: Int) {
        myFixture.editor.caretModel.moveToOffset(offset)
        UIUtil.dispatchAllInvocationEvents()
    }
}
