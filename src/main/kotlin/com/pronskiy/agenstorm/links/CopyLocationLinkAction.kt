package com.pronskiy.agenstorm.links

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.pronskiy.agenstorm.core.AgenstormSettings
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

/**
 * Editor and gutter popup action: copies `relpath:line[:col]` for the caret (or the selection start, or the
 * gutter line that was clicked). Plain text is the bare token; a second `text/markdown` flavor carries
 * `[File.php:42](relpath:42:7)`. No default shortcut on purpose (Marketplace etiquette); the README suggests one.
 */
class CopyLocationLinkAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = AgenstormSettings.getInstance().state.linksEnabled &&
            e.project != null &&
            e.getData(CommonDataKeys.EDITOR) != null &&
            e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val gutterLine = e.getData(EditorGutterComponentEx.LOGICAL_LINE_AT_CURSOR)
        val position = when {
            gutterLine != null -> LogicalPosition(gutterLine, 0)
            editor.selectionModel.hasSelection() -> editor.offsetToLogicalPosition(editor.selectionModel.selectionStart)
            else -> editor.caretModel.logicalPosition
        }
        val path = ApplicationManager.getApplication().runReadAction(Computable { relativePath(project, file) })
        val link = LocationLink(path, file.name, position.line + 1, position.column + 1)
        CopyPasteManager.getInstance().setContents(LocationLinkTransferable(link))
    }

    companion object {
        /** Second clipboard flavor with the Markdown form of the link. */
        val MARKDOWN_FLAVOR: DataFlavor = DataFlavor("text/markdown;class=java.lang.String", "Markdown")

        /** Path relative to the file's content root, else to the project directory, else absolute. */
        fun relativePath(project: Project, file: VirtualFile): String {
            val root = ProjectFileIndex.getInstance(project).getContentRootForFile(file) ?: project.guessProjectDir()
            return root?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.path
        }
    }
}

/** A location to copy; [line] and [column] are 1-based. Column 1 is left out of the token: it adds nothing. */
data class LocationLink(val path: String, val fileName: String, val line: Int, val column: Int) {
    val token: String
        get() = if (column > 1) "$path:$line:$column" else "$path:$line"

    val markdown: String
        get() = "[$fileName:$line]($token)"
}

class LocationLinkTransferable(private val link: LocationLink) : Transferable {

    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor, CopyLocationLinkAction.MARKDOWN_FLAVOR)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in transferDataFlavors

    override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
        DataFlavor.stringFlavor -> link.token
        CopyLocationLinkAction.MARKDOWN_FLAVOR -> link.markdown
        else -> throw UnsupportedFlavorException(flavor)
    }
}
