package com.pronskiy.agenstorm.markdown

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
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
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.intellij.plugins.markdown.lang.MarkdownFileType
import java.util.concurrent.ConcurrentHashMap

/**
 * Step F1.2. Owns one [LiveMarkupController] per main editor of a Markdown file in the project and the coroutine
 * scope they debounce on. Editors arrive through [LiveMarkupEditorListener]; the toggle action (F2.4) flips one
 * editor with [setEnabled], the settings page reaches every editor through [applySettings]. Mutations on the EDT;
 * [isActive] and [controllerFor] may be read from any thread (the annotator, the action's BGT update).
 */
@Service(Service.Level.PROJECT)
class LiveMarkupService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    private class Attachment(val controller: LiveMarkupController, val file: VirtualFile)

    private val attachments = ConcurrentHashMap<Editor, Attachment>()

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

    /** The toggle action: this editor's own choice wins over the global setting from now on. */
    fun setEnabled(editor: Editor, enabled: Boolean) {
        editor.putUserData(ENABLED_OVERRIDE, enabled)
        if (enabled) attach(editor) else detach(editor)
    }

    /** Settings changed: attach or detach every editor of this project as needed and re-collect the rest. */
    fun applySettings() {
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project !== project) continue
            val attached = attachments.containsKey(editor)
            val wanted = isEligible(editor)
            if (wanted && !attached) attach(editor) else if (!wanted && attached) detach(editor)
        }
        for (attachment in attachments.values) attachment.controller.requestSync()
    }

    /** [applySettings] on the EDT, also while a modal dialog (the settings page) is open. */
    fun applySettingsLater() {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) applySettings() }, ModalityState.any())
    }

    /** Keeps the per-file counter the annotator reads and re-runs the daemon when the file's state flips. */
    private fun markActive(file: VirtualFile, delta: Int) {
        val before = file.getUserData(ACTIVE_EDITORS) ?: 0
        val after = (before + delta).coerceAtLeast(0)
        file.putUserData(ACTIVE_EDITORS, after.takeIf { it > 0 })
        if ((before > 0) != (after > 0) && !project.isDisposed) restartDaemonFor(file)
    }

    /**
     * The daemon nudge needs a `PsiFile`, and the callers do not have a read action: `editorCreated` and
     * `editorReleased` arrive on the EDT straight from `EditorFactoryImpl`, which holds none. Looking the file up
     * inline logged one SEVERE per editor ("Read access is allowed from inside read-action only", the plugin named
     * as the one to blame), so the lookup goes to the scope inside a read action and only the restart returns to
     * the EDT. The restart itself needs no read access.
     */
    private fun restartDaemonFor(file: VirtualFile) {
        scope.launch {
            val psiFile = readAction {
                if (project.isDisposed || !file.isValid) null else PsiManager.getInstance(project).findFile(file)
            } ?: return@launch
            withContext(Dispatchers.EDT) {
                if (!project.isDisposed) {
                    DaemonCodeAnalyzer.getInstance(project).restart(psiFile, "Agenstorm live markup state changed")
                }
            }
        }
    }

    /**
     * An ordinary editor of this project over a Markdown file, while the feature is on — globally, or for this editor
     * through the toggle action. Consoles, diff viewers and previews are excluded by their kind; `UNTYPED` stays in
     * because `EditorFactory.createEditor(document, project, file, …)` produces it for plain file editors outside
     * `TextEditorProvider` (test fixtures included).
     */
    fun isEligible(editor: Editor): Boolean {
        if (editor.project !== project || editor !is EditorEx || editor.isDisposed) return false
        if (editor.editorKind != EditorKind.MAIN_EDITOR && editor.editorKind != EditorKind.UNTYPED) return false
        if (!(editor.getUserData(ENABLED_OVERRIDE) ?: AgenstormSettings.getInstance().state.liveMarkupEnabled)) return false
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
        /** Per-editor choice made with the toggle action; null = follow the global setting. */
        val ENABLED_OVERRIDE: Key<Boolean> = Key.create("agenstorm.liveMarkup.enabled")

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

/**
 * Registered in `agenstorm-markdown.xml`. Applies the settings to the editors that already exist (a plugin installed
 * without restart) and again whenever the settings page applies — the page lives in `core/` and must not know this
 * feature, so it speaks through [AgenstormSettingsListener].
 */
class LiveMarkupStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val service = LiveMarkupService.getInstance(project)
        ApplicationManager.getApplication().messageBus.connect(service).subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { service.applySettingsLater() })
        service.applySettingsLater()
    }
}
