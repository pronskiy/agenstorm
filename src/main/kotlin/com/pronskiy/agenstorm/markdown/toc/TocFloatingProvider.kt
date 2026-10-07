package com.pronskiy.agenstorm.markdown.toc

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarComponent
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarProvider
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import org.intellij.plugins.markdown.lang.MarkdownFileType

/**
 * Step W2.5 (decision 96). Puts [TocAction] — and so [TocPanel] — in the floating-toolbar slot in the top-right corner
 * of every Markdown file editor: the platform positions it, keeps it there while the text scrolls and disposes it with
 * the editor. Applicability is asked once, when the editor's toolbar is made, so it answers for the file type only;
 * the setting and the heading count live in the action's update. Never auto-hidden, and since Esc hides every floating
 * toolbar, it is shown again at once.
 */
class TocFloatingProvider : FloatingToolbarProvider {

    override val actionGroup: ActionGroup = DefaultActionGroup(TocAction())
    override val autoHideable: Boolean get() = false
    override val backgroundAlpha: Float get() = 0f
    override val showingTime: Int get() = SHOWING_TIME_MS

    override suspend fun isApplicableAsync(dataContext: DataContext): Boolean = appliesTo(dataContext)

    override fun register(dataContext: DataContext, component: FloatingToolbarComponent, parentDisposable: Disposable) {
        val editor = dataContext.getData(CommonDataKeys.EDITOR) as? EditorEx ?: return
        val project = editor.project ?: return
        if (project.isDisposed) return
        TocService.getInstance(project).attach(editor, component, parentDisposable)
        component.scheduleShow()
    }

    override fun onHiddenByEsc(dataContext: DataContext) {
        val editor = dataContext.getData(CommonDataKeys.EDITOR) ?: return
        val project = editor.project ?: return
        project.serviceIfCreated<TocService>()?.controllerFor(editor)?.toolbar?.scheduleShow()
    }

    companion object {
        const val SHOWING_TIME_MS = 150

        /** A main editor of a Markdown file in an open project; diffs and one-line editors never get the slot at all. */
        fun appliesTo(dataContext: DataContext): Boolean {
            val editor = dataContext.getData(CommonDataKeys.EDITOR) ?: return false
            if (editor.editorKind != EditorKind.MAIN_EDITOR && editor.editorKind != EditorKind.UNTYPED) return false
            val project = editor.project ?: return false
            if (project.isDisposed || project.isDefault) return false
            val file = dataContext.getData(CommonDataKeys.VIRTUAL_FILE)
                ?: FileDocumentManager.getInstance().getFile(editor.document)
                ?: return false
            return FileTypeRegistry.getInstance().isFileOfType(file, MarkdownFileType.INSTANCE)
        }
    }
}
