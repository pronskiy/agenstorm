package com.pronskiy.agenstorm.terminal.enhance

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import com.pronskiy.agenstorm.core.AgenstormSettings
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.plugins.terminal.block.util.TerminalDataContextUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns one [TerminalEnhancerController] per reworked-terminal output editor of the project and the scope they
 * scan on. Editors arrive through [TerminalEnhancerEditorListener]. The rules are the built-ins until Phase I3
 * adds the user's own.
 */
@Service(Service.Level.PROJECT)
class TerminalEnhancerService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    private val controllers = ConcurrentHashMap<Editor, TerminalEnhancerController>()
    private val builtIns: List<EnhancerRule> by lazy { BlockDetector.builtInRules() }

    fun rules(): List<EnhancerRule> = builtIns

    fun controllerFor(editor: Editor): TerminalEnhancerController? = controllers[editor]

    /** Attaches to an output editor when the feature is on; idempotent. */
    fun attach(editor: Editor): TerminalEnhancerController? {
        controllers[editor]?.let { return it }
        if (!AgenstormSettings.getInstance().state.terminalEnhancerEnabled || !isOutputEditor(editor)) return null
        val controller = TerminalEnhancerController(editor as EditorEx, ::rules, scope)
        controllers[editor] = controller
        return controller
    }

    fun detach(editor: Editor) {
        controllers.remove(editor)?.let { Disposer.dispose(it) }
    }

    /** Settings changed: attach to or detach from every output editor of this project as the toggle says. */
    fun applySettings() {
        val wanted = AgenstormSettings.getInstance().state.terminalEnhancerEnabled
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project !== project || !isOutputEditor(editor)) continue
            if (wanted) attach(editor) else detach(editor)
        }
    }

    /** [applySettings] on the EDT, also while a modal dialog (the settings page) is open. */
    fun applySettingsLater() {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) applySettings() }, ModalityState.any())
    }

    override fun dispose() {
        for (controller in controllers.values) Disposer.dispose(controller)
        controllers.clear()
    }

    companion object {
        fun getInstance(project: Project): TerminalEnhancerService = project.service()

        /** The reworked terminal's output editor — not its alternate-screen one, where `less` and `vim` live. */
        fun isOutputEditor(editor: Editor): Boolean = with(TerminalDataContextUtils) { editor.isOutputModelEditor }
    }
}

/**
 * Registered in `agenstorm-terminal.xml`. The terminal marks its output editor right *after* creating it, so the
 * mark is not there yet when [editorCreated] fires; one event later it is.
 */
class TerminalEnhancerEditorListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        ApplicationManager.getApplication().invokeLater({
            if (!editor.isDisposed && !project.isDisposed) TerminalEnhancerService.getInstance(project).attach(editor)
        }, ModalityState.any())
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val project = event.editor.project ?: return
        if (!project.isDisposed) TerminalEnhancerService.getInstance(project).detach(event.editor)
    }
}

/**
 * Registered in `agenstorm-terminal.xml`. Applies the toggle to the terminals a project already has, and again
 * whenever the settings page applies — the page lives in `core/` and speaks through [AgenstormSettingsListener].
 */
class TerminalEnhancerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = TerminalEnhancerService.getInstance(project)
        ApplicationManager.getApplication().messageBus.connect(service)
            .subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { service.applySettingsLater() })
        service.applySettingsLater()
    }
}
