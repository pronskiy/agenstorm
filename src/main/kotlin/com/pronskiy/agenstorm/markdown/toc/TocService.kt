package com.pronskiy.agenstorm.markdown.toc

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.codeStyle.CodeStyleSettingsChangeEvent
import com.intellij.psi.codeStyle.CodeStyleSettingsListener
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope

/**
 * Step W2.2. One [TocController] per Markdown editor whose floating toolbar took our provider, and the scope they
 * collect in. The settings page and a Code Style change (the right margin) reach every controller through
 * [refreshAll] — the page lives in `core/` and speaks through [AgenstormSettingsListener].
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

    /** Any thread: the action's BGT update reads it. */
    fun controllerFor(editor: Editor): TocController? = controllers[editor]

    /** The editor's floating toolbar registered our provider: a controller for it, disposed with [parent]. EDT. */
    fun attach(editor: EditorEx, toolbar: FloatingToolbarComponent?, parent: Disposable): TocController {
        controllers.remove(editor)?.let(Disposer::dispose)
        val controller = TocController(editor, project, scope, toolbar)
        controllers[editor] = controller
        Disposer.register(parent, controller)
        Disposer.register(controller, Disposable { controllers.remove(editor, controller) })
        return controller
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
    }
}
