package com.pronskiy.agenstorm.markdown

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.pronskiy.agenstorm.core.AgenstormSettings
import kotlinx.coroutines.CoroutineScope
import org.intellij.plugins.markdown.lang.MarkdownFileType

/**
 * Step F1.2. Owns one [LiveMarkupController] per main editor of a Markdown file in the project and the coroutine
 * scope they debounce on. Editors arrive through [LiveMarkupEditorListener]; the toggle and settings code of Phase F2
 * call [attach] / [detach] directly. EDT only, except [isActive], which the annotator reads from any thread.
 */
@Service(Service.Level.PROJECT)
class LiveMarkupService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    private class Attachment(val controller: LiveMarkupController, val file: VirtualFile)

    private val attachments = HashMap<Editor, Attachment>()

    fun controllerFor(editor: Editor): LiveMarkupController? = attachments[editor]?.controller

    /** Attaches live markup when [isEligible]; idempotent. Returns the controller in charge, or null when not attached. */
    fun attach(editor: Editor): LiveMarkupController? {
        attachments[editor]?.let { return it.controller }
        if (!isEligible(editor)) return null
        val file = fileOf(editor) ?: return null
        val controller = LiveMarkupController(editor as EditorEx, project, scope)
        attachments[editor] = Attachment(controller, file)
        markActive(file, +1)
        return controller
    }

    fun detach(editor: Editor) {
        val attachment = attachments.remove(editor) ?: return
        Disposer.dispose(attachment.controller)
        markActive(attachment.file, -1)
    }

    /** Keeps the per-file counter the annotator reads and re-runs the daemon when the file's state flips. */
    private fun markActive(file: VirtualFile, delta: Int) {
        val before = file.getUserData(ACTIVE_EDITORS) ?: 0
        val after = (before + delta).coerceAtLeast(0)
        file.putUserData(ACTIVE_EDITORS, after.takeIf { it > 0 })
        if ((before > 0) != (after > 0) && !project.isDisposed) {
            PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
        }
    }

    /**
     * An ordinary editor of this project over a Markdown file, while the feature is on. Consoles, diff viewers and
     * previews are excluded by their kind; `UNTYPED` stays in because `EditorFactory.createEditor(document, project,
     * file, …)` produces it for plain file editors outside `TextEditorProvider` (test fixtures included).
     */
    fun isEligible(editor: Editor): Boolean {
        if (editor.project !== project || editor !is EditorEx || editor.isDisposed) return false
        if (editor.editorKind != EditorKind.MAIN_EDITOR && editor.editorKind != EditorKind.UNTYPED) return false
        if (!AgenstormSettings.getInstance().state.liveMarkupEnabled) return false
        val file = fileOf(editor) ?: return false
        return FileTypeRegistry.getInstance().isFileOfType(file, MarkdownFileType.INSTANCE)
    }

    override fun dispose() {
        for (attachment in attachments.values) {
            Disposer.dispose(attachment.controller)
            attachment.file.putUserData(ACTIVE_EDITORS, null)
        }
        attachments.clear()
    }

    companion object {
        /** Number of live-markup editors over the file; set on the `VirtualFile`, so any thread may read it. */
        private val ACTIVE_EDITORS: Key<Int> = Key.create("agenstorm.liveMarkup.activeEditors")

        fun getInstance(project: Project): LiveMarkupService = project.service()

        /** True while at least one editor over [file] has live markup; the annotator's gate. */
        fun isActive(file: VirtualFile): Boolean = (file.getUserData(ACTIVE_EDITORS) ?: 0) > 0

        fun fileOf(editor: Editor): VirtualFile? = editor.virtualFile ?: FileDocumentManager.getInstance().getFile(editor.document)
    }
}

/**
 * Registered in `agenstorm-markdown.xml`. The public `EditorFactoryListener` is used instead of the Markdown plugin's
 * own hook, `TextEditorCustomizer`, because that interface is `@ApiStatus.Internal` and SPEC.md §2 does not list it.
 */
class LiveMarkupEditorListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val project = event.editor.project ?: return
        if (project.isDisposed || project.isDefault) return
        LiveMarkupService.getInstance(project).attach(event.editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val project = event.editor.project ?: return
        if (project.isDisposed || project.isDefault) return
        LiveMarkupService.getInstance(project).detach(event.editor)
    }
}
