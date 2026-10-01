package com.pronskiy.agenstorm.softwrap

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.editor.impl.SoftWrapModelImpl
import com.intellij.openapi.editor.impl.softwrap.mapping.SoftWrapApplianceManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.codeStyle.CodeStyleSettingsChangeEvent
import com.intellij.psi.codeStyle.CodeStyleSettingsListener
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Steps V1.2–V1.3 (decision 95). Soft wrap in Markdown and text files stops at the right margin. A main editor of a
 * file on the IDE's *Soft-wrap these files* list gets a [MarginWidthProvider] as it is created, which the platform reads
 * at the first layout and again on every resize, scroll and font change (the V0 spike). Two changes it does not
 * re-read the width for — the feature switched on or off with editors open, and the right margin changed in Code Style
 * — are made to by a scroll of one pixel and back: the calls that would do it directly (`updateAvailableArea`) are
 * internal. Diff views, consoles, the terminal and files off the list keep the platform's width. Should the `impl`
 * classes change shape, the editor is left as it was and the failure is logged once.
 */
@Service(Service.Level.APP)
class MarginWrapService : Disposable {

    private val failed = AtomicBoolean()

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { ApplicationManager.getApplication().invokeLater(::applyToOpenEditors) })
    }

    /** A new editor: capped from its first layout when the feature is on and the file is on the list. */
    fun attach(editor: Editor) {
        if (enabled() && eligible(editor)) install(editor)
    }

    /** The setting changed: every open editor capped or given its own width back, and re-wrapped. */
    fun applyToOpenEditors() {
        val on = enabled()
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.isDisposed) continue
            val changed = if (on && eligible(editor)) install(editor) else uninstall(editor)
            if (changed) nudge(editor)
        }
    }

    /** The right margin may have moved for [project]'s files: its capped editors re-wrap. */
    fun marginChanged(project: Project) {
        for (editor in EditorFactory.getInstance().allEditors) {
            if (!editor.isDisposed && editor.project == project && manager(editor)?.widthProvider is MarginWidthProvider) nudge(editor)
        }
    }

    private fun enabled(): Boolean = AgenstormSettings.getInstance().state.softWrapAtRightMargin

    private fun eligible(editor: Editor): Boolean {
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return false
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
        return MarginWrap.matches(EditorSettingsExternalizable.getInstance().softWrapFileMasks, file.name)
    }

    private fun install(editor: Editor): Boolean {
        val manager = manager(editor) ?: return false
        val current = manager.widthProvider
        if (current is MarginWidthProvider) return false
        manager.setWidthProvider(MarginWidthProvider(editor, current))
        return true
    }

    private fun uninstall(editor: Editor): Boolean {
        val manager = manager(editor) ?: return false
        val capped = manager.widthProvider as? MarginWidthProvider ?: return false
        manager.setWidthProvider(capped.original)
        return true
    }

    private fun manager(editor: Editor): SoftWrapApplianceManager? = try {
        ((editor as? EditorEx)?.softWrapModel as? SoftWrapModelImpl)?.applianceManager
    } catch (e: LinkageError) {
        if (failed.compareAndSet(false, true)) LOG.warn("Agenstorm: soft wrap at the right margin is unavailable in this build", e)
        null
    }

    /**
     * Makes the platform read the width again: a visible-area change runs its `updateAvailableArea()`. One pixel down
     * and back, without animation, so nothing moves on screen; a document that fits the window cannot scroll and keeps
     * its width until its next resize or zoom.
     */
    private fun nudge(editor: Editor) {
        val scrolling = editor.scrollingModel
        val offset = scrolling.verticalScrollOffset
        scrolling.disableAnimation()
        try {
            scrolling.scrollVertically(offset + 1)
            scrolling.scrollVertically(offset)
        } finally {
            scrolling.enableAnimation()
        }
    }

    override fun dispose() = Unit

    companion object {
        private val LOG = logger<MarginWrapService>()

        fun getInstance(): MarginWrapService = service()
    }
}

/** Step V1.2: every new editor passes through [MarginWrapService.attach]. Registered in `plugin.xml`. */
class MarginWrapEditorListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) = MarginWrapService.getInstance().attach(event.editor)
}

/** Step V1.3: a Code Style change may move the right margin; the platform does not re-wrap for it. Registered in `plugin.xml`. */
class MarginWrapCodeStyleListener(private val project: Project) : CodeStyleSettingsListener {
    override fun codeStyleSettingsChanged(event: CodeStyleSettingsChangeEvent) {
        // The Code Style page is a modal dialog; the editors behind it re-wrap while it is still open.
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) MarginWrapService.getInstance().marginChanged(project) }, ModalityState.any())
    }
}
