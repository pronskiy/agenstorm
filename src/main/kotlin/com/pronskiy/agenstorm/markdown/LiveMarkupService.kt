package com.pronskiy.agenstorm.markdown

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
import com.pronskiy.agenstorm.core.AgenstormSettings
import kotlinx.coroutines.CoroutineScope
import org.intellij.plugins.markdown.lang.MarkdownFileType

/**
 * Step F1.2. Owns one [LiveMarkupController] per main editor of a Markdown file in the project and the coroutine
 * scope they debounce on. Editors arrive through [LiveMarkupEditorListener]; the toggle and settings code of Phase F2
 * call [attach] / [detach] directly. EDT only.
 */
@Service(Service.Level.PROJECT)
class LiveMarkupService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    private val controllers = HashMap<Editor, LiveMarkupController>()

    fun controllerFor(editor: Editor): LiveMarkupController? = controllers[editor]

    /** Attaches live markup when [isEligible]; idempotent. Returns the controller in charge, or null when not attached. */
    fun attach(editor: Editor): LiveMarkupController? {
        controllers[editor]?.let { return it }
        if (!isEligible(editor)) return null
        val controller = LiveMarkupController(editor as EditorEx, project, scope)
        controllers[editor] = controller
        return controller
    }

    fun detach(editor: Editor) {
        controllers.remove(editor)?.let(Disposer::dispose)
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
        val file = editor.virtualFile ?: FileDocumentManager.getInstance().getFile(editor.document) ?: return false
        return FileTypeRegistry.getInstance().isFileOfType(file, MarkdownFileType.INSTANCE)
    }

    override fun dispose() {
        controllers.values.forEach(Disposer::dispose)
        controllers.clear()
    }

    companion object {
        fun getInstance(project: Project): LiveMarkupService = project.service()
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
