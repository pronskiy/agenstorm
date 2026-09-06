package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.DumbAware
import org.intellij.plugins.markdown.lang.MarkdownFileType

/**
 * Step F2.4. "Live Markup" in the Markdown editor toolbar and the editor context menu: on or off for this editor,
 * overriding the global setting until the editor closes. Shown only for Markdown editors.
 */
class ToggleLiveMarkupAction : ToggleAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = editorOf(e)
        val project = e.project
        val file = editor?.let(LiveMarkupService::fileOf)
        val applicable = editor != null && project != null && !project.isDisposed && file != null &&
            FileTypeRegistry.getInstance().isFileOfType(file, MarkdownFileType.INSTANCE)
        e.presentation.isEnabledAndVisible = applicable
        if (applicable) super.update(e)
    }

    override fun isSelected(e: AnActionEvent): Boolean {
        val editor = editorOf(e) ?: return false
        val project = e.project ?: return false
        return LiveMarkupService.getInstance(project).controllerFor(editor) != null
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val editor = editorOf(e) ?: return
        val project = e.project ?: return
        LiveMarkupService.getInstance(project).setEnabled(editor, state)
    }

    companion object {
        /** The editor behind the event: directly, or through the (split) file editor the toolbar belongs to. */
        fun editorOf(e: AnActionEvent): Editor? {
            e.getData(CommonDataKeys.EDITOR)?.let { return it }
            return when (val fileEditor = e.getData(PlatformCoreDataKeys.FILE_EDITOR)) {
                is TextEditorWithPreview -> fileEditor.textEditor.editor
                is TextEditor -> fileEditor.editor
                else -> null
            }
        }
    }
}
