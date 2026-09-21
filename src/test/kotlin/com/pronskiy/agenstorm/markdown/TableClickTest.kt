package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.markdown.tables.TableInlayRenderer
import java.awt.Point

/**
 * Step Q2.4: a plain click on the rendered table follows a link, or opens the clicked cell in place (decisions 68
 * and 69, the latter replacing the reveal-at-cell of the first); hovering a link is reported for the hand cursor.
 *
 * The document is `Above` · blank · `| a | b |` · `|---|---|` · `| [l](https://example.com) | d |` · blank ·
 * `Below`: the table region is (6,59), the link text sits at 30, the `d` cell's content at 56.
 */
class TableClickTest : BasePlatformTestCase() {

    private val text = "Above\n\n| a | b |\n|---|---|\n| [l](https://example.com) | d |\n\nBelow\n"

    private val browsed = ArrayList<String>()

    override fun setUp() {
        super.setUp()
        LinkDestinations.browserOverride = { browsed += it }
    }

    override fun tearDown() {
        try {
            LinkDestinations.browserOverride = null
        } finally {
            super.tearDown()
        }
    }

    fun testAClickOnACellOpensThatCellAndLeavesTheTableRendered() {
        val controller = configured()
        assertTrue(controller.clickTable(pointOn(controller, row = 1, column = 1)))
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(1 to 1, controller.cellEditor().position)
        assertEquals("d", controller.cellEditor().field?.text)
        assertFalse(tableRegionExpanded(controller))
        assertSize(1, controller.tableInlays())
    }

    fun testAClickOnALinkOpensItAndLeavesTheTableRendered() {
        val controller = configured()
        assertTrue(controller.clickTable(pointOn(controller, row = 1, column = 0)))
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(listOf("https://example.com"), browsed)
        assertFalse(tableRegionExpanded(controller))
        assertEquals(0, myFixture.editor.caretModel.offset)
        assertSize(1, controller.tableInlays())
    }

    fun testAClickOnAFileLinkOpensTheFile() {
        myFixture.addFileToProject("other.md", "elsewhere\n")
        val controller = configured("Above\n\n| a | b |\n|---|---|\n| [o](other.md) | d |\n\nBelow\n")
        assertTrue(controller.clickTable(pointOn(controller, row = 1, column = 0)))
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("other.md", FileEditorManager.getInstance(project).selectedFiles.single().name)
        assertEmpty(browsed)
    }

    fun testAClickOffTheTableIsNotOurs() {
        val controller = configured()
        assertFalse(controller.clickTable(Point(0, 0)))
        assertFalse(tableRegionExpanded(controller))
    }

    fun testALinkUnderThePointerIsReported() {
        val controller = configured()
        assertTrue(controller.tableLinkAt(pointOn(controller, row = 1, column = 0)))
        assertFalse(controller.tableLinkAt(pointOn(controller, row = 1, column = 1)))
        assertFalse(controller.tableLinkAt(Point(0, 0)))
    }

    private fun configured(document: String = text): LiveMarkupController {
        myFixture.configureByText("a.md", document)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val controller = LiveMarkupService.getInstance(project).controllerFor(myFixture.editor)
            ?: error("no live markup controller on the Markdown editor")
        controller.syncNow()
        return controller
    }

    /** A point one pixel inside the first run of the cell, in the editor content component's coordinates. */
    private fun pointOn(controller: LiveMarkupController, row: Int, column: Int): Point {
        val inlay: Inlay<TableInlayRenderer> = controller.tableInlays().single()
        val bounds = inlay.bounds ?: error("the inlay has no bounds")
        val cell = inlay.renderer.geometry().cells.single { it.row == row && it.column == column }
        val run = cell.runs.first()
        return Point(bounds.x + run.x + 1, bounds.y + JBUIScale.scale(TableInlayRenderer.MARGIN) + run.y + 1)
    }

    private fun tableRegionExpanded(controller: LiveMarkupController): Boolean =
        controller.regions().single { it.getUserData(LiveMarkupController.KIND) == MarkupKind.TABLE }.isExpanded
}
