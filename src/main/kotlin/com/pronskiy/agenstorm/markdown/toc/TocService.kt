package com.pronskiy.agenstorm.markdown.toc

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.psi.codeStyle.CodeStyleSettingsChangeEvent
import com.intellij.psi.codeStyle.CodeStyleSettingsListener
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.plugins.markdown.lang.MarkdownFileType

/**
 * Steps W2.2 and W2.8. One [TocController] — and its [TocLayer] — per Markdown editor of the project, and the scope they
 * collect in. Editors arrive through [TocEditorListener], and those open before the plugin loaded through
 * [TocStartupActivity]. The settings page (the depth, the switch, the fold choice) and a Code Style change (the right
 * margin) reach every controller through [refreshAll] — the page lives in `core/` and speaks through
 * [AgenstormSettingsListener].
 */
@Service(Service.Level.PROJECT)
class TocService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    private val controllers = ConcurrentHashMap<Editor, TocController>()

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { refreshAllLater() })
        project.messageBus.connect(this).subscribe(CodeStyleSettingsListener.TOPIC, object : CodeStyleSettingsListener {
            override fun codeStyleSettingsChanged(event: CodeStyleSettingsChangeEvent) = refreshAllLater()
        })
    }

    /** Any thread. */
    fun controllerFor(editor: Editor): TocController? = controllers[editor]

    /** A controller and a widget for [editor], replacing any it had, disposed with [parent] or by [detach]. EDT. */
    fun attach(editor: EditorEx, parent: Disposable = this): TocController {
        controllers.remove(editor)?.let(Disposer::dispose)
        val controller = TocController(editor, project, scope)
        controllers[editor] = controller
        Disposer.register(parent, controller)
        Disposer.register(controller, Disposable { controllers.remove(editor, controller) })
        TocLayer.install(controller)
        return controller
    }

    /** EDT. */
    fun detach(editor: Editor) {
        controllers.remove(editor)?.let(Disposer::dispose)
    }

    /** Every eligible editor of this project that has no widget yet: a plugin installed while files are open. EDT. */
    fun attachOpenEditors() {
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project === project && isEligible(editor) && controllers[editor] == null) attach(editor as EditorEx)
        }
    }

    /** EDT. */
    fun refreshAll() {
        for (controller in controllers.values) controller.refresh()
    }

    /** The settings page and Code Style are modal dialogs; the editors behind them follow while they are open. */
    private fun refreshAllLater() {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) refreshAll() }, ModalityState.any())
    }

    override fun dispose() {
        controllers.clear()
    }

    companion object {
        fun getInstance(project: Project): TocService = project.service()

        /**
         * A main editor of a Markdown file in an open project. `UNTYPED` stays in because plain file editors made outside
         * `TextEditorProvider` (test fixtures included) have it; diffs, consoles and previews are other kinds.
         */
        fun isEligible(editor: Editor): Boolean {
            if (editor !is EditorEx || editor.isDisposed || editor.isOneLineMode) return false
            if (editor.editorKind != EditorKind.MAIN_EDITOR && editor.editorKind != EditorKind.UNTYPED) return false
            val project = editor.project ?: return false
            if (project.isDisposed || project.isDefault) return false
            val file = editor.virtualFile ?: FileDocumentManager.getInstance().getFile(editor.document) ?: return false
            return FileTypeRegistry.getInstance().isFileOfType(file, MarkdownFileType.INSTANCE)
        }
    }
}

/** Registered in `agenstorm-markdown.xml`: a Markdown editor gets its widget when it opens and loses it when it closes. */
class TocEditorListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (!TocService.isEligible(editor)) return
        TocService.getInstance(editor.project ?: return).attach(editor as EditorEx)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val project = event.editor.project ?: return
        if (project.isDisposed || project.isDefault) return
        TocService.getInstance(project).detach(event.editor)
    }
}

/** Registered in `agenstorm-markdown.xml`: editors already open when the plugin loads get their widget too. */
class TocStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        withContext(Dispatchers.EDT) {
            if (!project.isDisposed) TocService.getInstance(project).attachOpenEditors()
        }
    }
}
