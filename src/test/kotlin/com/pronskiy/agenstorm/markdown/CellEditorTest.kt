package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.markdown.tables.CellEditor
import com.pronskiy.agenstorm.markdown.tables.TableInlayRenderer
import java.awt.Point
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * Steps Q3.2 and Q3.3: a plain click on a rendered table opens a field over the clicked cell holding its raw
 * Markdown while the table stays rendered; Enter writes the text back in one undoable command, Esc writes nothing,
 * Tab and Shift+Tab commit and move along the row, and a cell the row does not have is appended.
 *
 * The document is `Above` · blank · `| a | b |` · `|---|---|` · `| **c** | d |` · blank · `Below`: the table
 * region is (6,40), the first body cell's content `**c**` is (29,34), the second's `d` is (37,38).
 */
class CellEditorTest : BasePlatformTestCase() {

    private val text = "Above\n\n| a | b |\n|---|---|\n| **c** | d |\n\nBelow\n"

    fun testAClickOpensAFieldOverTheCellWithItsRawMarkdownAndTheTableStaysRendered() {
        val controller = configured()
        assertTrue(controller.clickTable(pointOn(controller, row = 1, column = 0)))
        val cellEditor = controller.cellEditor()
        assertTrue(cellEditor.isOpen)
        val field = cellEditor.field ?: error("no field")
        assertEquals("**c**", field.text)
        assertSame(myFixture.editor.contentComponent, field.parent)
        val inlay = controller.tableInlays().single()
        assertTrue("${field.bounds} inside ${inlay.bounds}", inlay.bounds!!.contains(field.bounds))
        assertFalse(tableRegion(controller).isExpanded)
        assertEquals(0, myFixture.editor.caretModel.offset)
    }

    fun testCommitWritesTheCellBackAsOneUndoableStep() {
        val controller = configured()
        controller.clickTable(pointOn(controller, row = 1, column = 0))
        controller.cellEditor().field!!.text = "**cc**"
        assertTrue(controller.cellEditor().commit())
        assertFalse(controller.cellEditor().isOpen)
        assertEquals(text.replace("| **c** | d |", "| **cc** | d |"), myFixture.editor.document.text)
        assertTrue("the table is rendered again", controller.tableInlays().single().renderer.model.rows[0].cells[0].runs.single().text == "cc")

        UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).getSelectedEditor(myFixture.file.virtualFile))
        assertEquals(text, myFixture.editor.document.text)
    }

    fun testCancelLeavesTheFileUntouched() {
        val controller = configured()
        controller.clickTable(pointOn(controller, row = 1, column = 0))
        val stamp = myFixture.editor.document.modificationStamp
        controller.cellEditor().field!!.text = "zzz"
        controller.cellEditor().cancel()
        assertFalse(controller.cellEditor().isOpen)
        assertEquals(text, myFixture.editor.document.text)
        assertEquals(stamp, myFixture.editor.document.modificationStamp)
        assertNull(myFixture.editor.contentComponent.components.firstOrNull { it is javax.swing.text.JTextComponent })
    }

    fun testCommitWithoutAChangeWritesNothing() {
        val controller = configured()
        controller.clickTable(pointOn(controller, row = 1, column = 1))
        val stamp = myFixture.editor.document.modificationStamp
        assertTrue(controller.cellEditor().commit())
        assertEquals(stamp, myFixture.editor.document.modificationStamp)
    }

    fun testAMissingCellIsAppendedToItsRow() {
        val controller = configured("Above\n\n| a | b |\n|---|---|\n| only |\n\nBelow\n")
        assertTrue(controller.clickTable(pointOn(controller, row = 1, column = 1)))
        assertEquals("", controller.cellEditor().field!!.text)
        controller.cellEditor().field!!.text = "new"
        controller.cellEditor().commit()
        assertEquals("Above\n\n| a | b |\n|---|---|\n| only | new |\n\nBelow\n", myFixture.editor.document.text)
    }

    fun testTabCommitsAndOpensTheNextCellShiftTabThePrevious() {
        val controller = configured()
        controller.clickTable(pointOn(controller, row = 1, column = 0))
        controller.cellEditor().field!!.text = "x"
        controller.cellEditor().moveToNext()
        assertEquals("| x | d |", myFixture.editor.document.getText(com.intellij.openapi.util.TextRange(27, 36)))
        assertTrue(controller.cellEditor().isOpen)
        assertEquals(1 to 1, controller.cellEditor().position)
        assertEquals("d", controller.cellEditor().field!!.text)

        controller.cellEditor().moveToPrevious()
        assertEquals(1 to 0, controller.cellEditor().position)
        assertEquals("x", controller.cellEditor().field!!.text)
    }

    fun testTabFromTheLastCellWrapsToTheNextRowAndClosesAfterTheLast() {
        val controller = configured()
        controller.clickTable(pointOn(controller, row = 0, column = 1))
        controller.cellEditor().moveToNext()
        assertEquals(1 to 0, controller.cellEditor().position)
        controller.cellEditor().moveToNext()
        assertEquals(1 to 1, controller.cellEditor().position)
        controller.cellEditor().moveToNext()
        assertFalse(controller.cellEditor().isOpen)
    }

    fun testTheFieldBindsEnterEscapeAndTab() {
        val controller = configured()
        controller.clickTable(pointOn(controller, row = 1, column = 0))
        val field = controller.cellEditor().field!!
        val map = field.getInputMap(JComponent.WHEN_FOCUSED)
        for (key in listOf("ENTER", "ESCAPE", "TAB", "shift TAB")) {
            assertNotNull("$key is bound", map[KeyStroke.getKeyStroke(key)])
        }
        assertFalse("Tab reaches the binding, not focus traversal", field.focusTraversalKeysEnabled)
    }

    fun testFocusLossCommitsOnTheNextEventLoopTurnNotInsideTheFocusEvent() {
        // Found in the sandbox: an AWT focus event is dispatched without the write-intent lock, and committing the
        // document from inside it is "Access is allowed from write thread only". The commit is deferred one turn.
        val controller = configured()
        controller.clickTable(pointOn(controller, row = 1, column = 0))
        val field = controller.cellEditor().field!!
        field.text = "late"
        for (listener in field.focusListeners) listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST, false))
        assertTrue("still open inside the focus event", controller.cellEditor().isOpen)
        assertEquals(text, myFixture.editor.document.text)
        UIUtil.dispatchAllInvocationEvents()
        assertFalse(controller.cellEditor().isOpen)
        assertEquals("| late | d |", myFixture.editor.document.getText(com.intellij.openapi.util.TextRange(27, 39)))
    }

    fun testATemporaryFocusLossCommitsNothing() {
        val controller = configured()
        controller.clickTable(pointOn(controller, row = 1, column = 0))
        val field = controller.cellEditor().field!!
        for (listener in field.focusListeners) listener.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST, true))
        UIUtil.dispatchAllInvocationEvents()
        assertTrue(controller.cellEditor().isOpen)
    }

    fun testAClickOnAnotherCellCommitsTheOpenOne() {
        val controller = configured()
        controller.clickTable(pointOn(controller, row = 1, column = 0))
        controller.cellEditor().field!!.text = "y"
        assertTrue(controller.clickTable(pointOn(controller, row = 1, column = 1)))
        assertEquals("| y | d |", myFixture.editor.document.getText(com.intellij.openapi.util.TextRange(27, 36)))
        assertEquals(1 to 1, controller.cellEditor().position)
    }

    private fun configured(document: String = text): LiveMarkupController {
        myFixture.configureByText("a.md", document)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val controller = LiveMarkupService.getInstance(project).controllerFor(myFixture.editor)
            ?: error("no live markup controller on the Markdown editor")
        controller.syncNow()
        return controller
    }

    private fun pointOn(controller: LiveMarkupController, row: Int, column: Int): Point {
        val inlay: Inlay<TableInlayRenderer> = controller.tableInlays().single()
        val bounds = inlay.renderer.cellBounds(inlay, row, column) ?: error("no cell ($row, $column)")
        return Point(bounds.x + 2, bounds.y + 2)
    }

    private fun tableRegion(controller: LiveMarkupController) =
        controller.regions().single { it.getUserData(LiveMarkupController.KIND) == MarkupKind.TABLE }
}
