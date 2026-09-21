package com.pronskiy.agenstorm.markdown.tables

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.components.JBTextField
import com.pronskiy.agenstorm.core.AgenstormBundle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * Steps Q3.2 and Q3.3 (decision 69). Edits one cell of a rendered table in place: a text field laid over the
 * clicked cell — a child of the editor's content component with the cell's own bounds, the way Epic O's
 * `InlineNameEditor` sits on the tree — holding the cell's raw Markdown exactly as written. [commit] writes the
 * text back into the cell's range in one command, so one Undo reverts one cell, and [resync] renders the table
 * again at once; [cancel] writes nothing. Tab and Shift+Tab commit and open the neighbouring cell. A cell the row
 * does not have (its range is empty at the row's end) is appended as ` text |`.
 *
 * The field scrolls with the content component, so nothing has to follow the viewport; [reposition] only runs
 * when the table is laid out again (a resize) and the cell moved.
 */
class CellEditor(private val editor: EditorEx, private val project: Project, private val resync: () -> Unit) : Disposable {

    private class Session(
        val inlay: Inlay<TableInlayRenderer>,
        val tableStart: Int,
        val row: Int,
        val column: Int,
        val marker: RangeMarker,
        val missing: Boolean,
        val field: JBTextField,
        val original: String,
    )

    private var session: Session? = null
    private var closing = false

    val isOpen: Boolean get() = session != null

    /** The field while a cell is open. */
    val field: JBTextField? get() = session?.field

    /** The open cell as (row, column), row 0 being the header. */
    val position: Pair<Int, Int>? get() = session?.let { it.row to it.column }

    /** EDT. Opens the cell; an open cell is committed first. False when the inlay has no such cell on screen. */
    fun open(inlay: Inlay<TableInlayRenderer>, row: Int, column: Int): Boolean {
        if (session != null) commit()
        if (!inlay.isValid) return false
        val model = inlay.renderer.model
        val tableRow = (listOf(model.header) + model.rows).getOrNull(row) ?: return false
        val cell = tableRow.cells.getOrNull(column) ?: return false
        val bounds = inlay.renderer.cellBounds(inlay, row, column) ?: return false
        val document = editor.document
        val missing = cell.range.isEmpty && cell.range.startOffset == tableRow.range.endOffset
        val original = if (missing) "" else document.getText(cell.range)
        val field = JBTextField(original)
        field.font = inlay.renderer.measurer().fonts.text
        field.focusTraversalKeysEnabled = false
        field.bounds = bounds
        field.registerKeyboardAction({ commit() }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)
        field.registerKeyboardAction({ cancel() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED)
        field.registerKeyboardAction({ moveToNext() }, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), JComponent.WHEN_FOCUSED)
        field.registerKeyboardAction({ moveToPrevious() }, KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK), JComponent.WHEN_FOCUSED)
        field.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                if (!closing && session?.field === field && !e.isTemporary) commit()
            }
        })
        val marker = document.createRangeMarker(cell.range)
        session = Session(inlay, inlay.offset, row, column, marker, missing, field, original)
        val content = editor.contentComponent
        content.add(field)
        field.revalidate()
        content.repaint(bounds)
        focus(field)
        return true
    }

    /** EDT. Writes the field back when it changed, closes, and renders the table again. False when nothing was open. */
    fun commit(): Boolean {
        val current = session ?: return false
        val text = current.field.text
        if (text != current.original && current.marker.isValid) {
            WriteCommandAction.runWriteCommandAction(project, AgenstormBundle.message("markdown.editCell.command"), COMMAND_GROUP, {
                val document = editor.document
                val range = current.marker.textRange
                if (current.missing) {
                    val row = document.getLineNumber(range.endOffset)
                    val rowText = document.getText(TextRange(document.getLineStartOffset(row), range.endOffset))
                    document.insertString(range.endOffset, if (rowText.trimEnd().endsWith("|")) " $text |" else " | $text")
                } else {
                    document.replaceString(range.startOffset, range.endOffset, text)
                }
            })
        }
        close()
        resync()
        return true
    }

    /** EDT. Closes without writing. */
    fun cancel() {
        if (session == null) return
        close()
    }

    /** EDT. Commits and opens the next cell along the row, wrapping to the next row; closes after the last cell. */
    fun moveToNext() = move(1)

    /** EDT. Commits and opens the previous cell, wrapping to the row above; closes before the first cell. */
    fun moveToPrevious() = move(-1)

    /** EDT. The table was laid out again: the field follows its cell, or closes if the cell is gone. */
    fun reposition(inlays: List<Inlay<TableInlayRenderer>>) {
        val current = session ?: return
        val inlay = inlays.firstOrNull { it.offset == current.tableStart } ?: return cancel()
        val bounds = inlay.renderer.cellBounds(inlay, current.row, current.column) ?: return cancel()
        if (current.field.bounds != bounds) {
            current.field.bounds = bounds
            editor.contentComponent.repaint()
        }
    }

    override fun dispose() {
        if (session != null) close()
    }

    private fun move(step: Int) {
        val current = session ?: return
        val model = current.inlay.renderer.model
        val columns = model.columnCount
        val rows = 1 + model.rows.size
        var row = current.row
        var column = current.column + step
        if (column >= columns) {
            column = 0
            row++
        } else if (column < 0) {
            column = columns - 1
            row--
        }
        val tableStart = current.tableStart
        commit()
        if (row < 0 || row >= rows) return
        val inlay = editor.inlayModel.getBlockElementsInRange(tableStart, tableStart)
            .firstOrNull { it.renderer is TableInlayRenderer && it.offset == tableStart }
            ?: return
        @Suppress("UNCHECKED_CAST")
        open(inlay as Inlay<TableInlayRenderer>, row, column)
    }

    private fun close() {
        val current = session ?: return
        closing = true
        try {
            session = null
            current.marker.dispose()
            val content = editor.contentComponent
            content.remove(current.field)
            content.repaint(current.field.bounds)
            if (!editor.isDisposed) IdeFocusManager.getInstance(project).requestFocus(content, true)
        } finally {
            closing = false
        }
    }

    /** The first request is asynchronous and may lose to the editor's own; ask again on the next turn (Epic O). */
    private fun focus(field: JBTextField) {
        field.requestFocusInWindow()
        ApplicationManager.getApplication().invokeLater({
            if (session?.field === field && !field.hasFocus()) field.requestFocusInWindow()
        }) { session?.field !== field }
    }

    companion object {
        private const val COMMAND_GROUP = "agenstorm.markdown.editCell"
    }
}
