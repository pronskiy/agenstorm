package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.LayoutActionsFloatingToolbar
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import org.intellij.plugins.markdown.lang.MarkdownFileType
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JLayeredPane

/**
 * Epic M. Takes the editor/preview layout buttons out of the top-right corner of Markdown editors.
 *
 * `TextEditorWithPreview` builds its own `LayoutActionsFloatingToolbar` and adds it to a `JBLayeredPane` at
 * [JLayeredPane.POPUP_LAYER], above the splitter. Neither the editor (`MarkdownEditorWithPreview` is `final`) nor its
 * provider (`MarkdownSplitEditorProvider` is `final` and `@ApiStatus.Internal`) can be subclassed to answer `false`
 * from `isShowFloatingToolbar()`, and the registry key behind that method swaps the floating pill for a *permanent*
 * toolbar strip rather than removing it. So the toolbar is taken out of its parent instead — decision 47.
 *
 * `setVisible(false)` is not enough: the toolbar's animator re-runs `setVisible(isVisible && hasVisibleActions())` on
 * every tick and would put it straight back. Detaching is stable, and the wrapper's `doLayout` places the toolbar by
 * type rather than by child index, so re-adding it restores the original position.
 *
 * Swing only, so every method here runs on the EDT. What was detached is parked on the `FileEditor`'s own user data
 * and dies with it; nothing outside this service holds on to it.
 */
@Service(Service.Level.PROJECT)
class LayoutSwitcherHider(private val project: Project) : Disposable {

    /** Hides or restores the layout buttons of every open Markdown editor, following the current setting. */
    fun apply() {
        if (project.isDisposed) return
        val hide = AgenstormSettings.getInstance().state.markdownHideLayoutSwitcher
        for (fileEditor in FileEditorManager.getInstance(project).allEditors) applyTo(fileEditor, hide)
    }

    /** [apply] on the EDT, also while a modal dialog (the settings page) is open. */
    fun applyLater() {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) apply() }, ModalityState.any())
    }

    /** The editors of one freshly opened file; [apply] would walk every tab in the project for nothing. */
    fun applyToFile(file: VirtualFile) {
        if (project.isDisposed) return
        if (!AgenstormSettings.getInstance().state.markdownHideLayoutSwitcher) return
        for (fileEditor in FileEditorManager.getInstance(project).getAllEditors(file)) applyTo(fileEditor, true)
    }

    private fun applyTo(fileEditor: FileEditor, hide: Boolean) {
        if (fileEditor !is TextEditorWithPreview || !fileEditor.isValid) return
        val file = fileEditor.file ?: return
        if (!FileTypeRegistry.getInstance().isFileOfType(file, MarkdownFileType.INSTANCE)) return
        if (hide) hide(fileEditor) else restore(fileEditor)
    }

    private fun hide(fileEditor: FileEditor) {
        if (fileEditor.getUserData(DETACHED) != null) return
        // Asking for the component is what makes TextEditorWithPreview build its UI, the floating toolbar included.
        fileEditor.putUserData(DETACHED, detachFrom(fileEditor.component) ?: return)
    }

    private fun restore(fileEditor: FileEditor) {
        val detached = fileEditor.getUserData(DETACHED) ?: return
        fileEditor.putUserData(DETACHED, null)
        reattach(detached)
    }

    /** Plugin unload and project close put every toolbar back, the way an [com.pronskiy.agenstorm.core.ActionSlot] does. */
    override fun dispose() {
        if (project.isDisposed) return
        for (fileEditor in FileEditorManager.getInstance(project).allEditors) restore(fileEditor)
    }

    /** A floating toolbar, the container it was taken out of and the layer it sat on, so the three go back together. */
    class Detached(val toolbar: JComponent, val parent: Container, val layer: Int)

    companion object {
        private val DETACHED: Key<Detached> = Key.create("agenstorm.markdown.detachedLayoutToolbar")

        fun getInstance(project: Project): LayoutSwitcherHider = project.service()

        /**
         * The floating pill somewhere under [root]. Written out rather than handed to `UIUtil.uiTraverser` so the
         * search stops at the first hit: one `TextEditorWithPreview` has exactly one of these.
         */
        fun findLayoutToolbar(root: Component): JComponent? {
            if (root is LayoutActionsFloatingToolbar) return root
            if (root !is Container) return null
            for (child in root.components) findLayoutToolbar(child)?.let { return it }
            return null
        }

        /** Takes the layout toolbar under [root] out of the tree. Null when there is none, or it has no parent. */
        fun detachFrom(root: Component): Detached? {
            val toolbar = findLayoutToolbar(root) ?: return null
            val parent = toolbar.parent ?: return null
            val layer = JLayeredPane.getLayer(toolbar)
            parent.remove(toolbar)
            parent.revalidate()
            parent.repaint()
            return Detached(toolbar, parent, layer)
        }

        /**
         * Puts back what [detachFrom] took. The layer matters — the toolbar is painted over the splitter — and it is
         * restored through [JLayeredPane.putLayer] rather than as an `add` constraint: from Kotlin,
         * `add(component, POPUP_LAYER)` binds to `Container.add(Component, int)` and passes the layer as a child
         * index, which silently leaves the toolbar on layer 0.
         */
        fun reattach(detached: Detached) {
            JLayeredPane.putLayer(detached.toolbar, detached.layer)
            detached.parent.add(detached.toolbar)
            detached.parent.revalidate()
            detached.parent.repaint()
        }
    }
}

/**
 * Registered in `agenstorm-markdown.xml`. A Markdown file opening after the setting went on gets its own toolbar
 * detached; editors that were already open are handled by [LayoutSwitcherStartupActivity].
 */
class LayoutSwitcherEditorListener : FileEditorManagerListener {

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val project = source.project
        if (project.isDisposed) return
        LayoutSwitcherHider.getInstance(project).applyToFile(file)
    }
}

/**
 * Registered in `agenstorm-markdown.xml`. Applies the setting to the editors that already exist and again whenever the
 * settings page applies — the page lives in `core/` and must not know this feature, so it speaks through
 * [AgenstormSettingsListener].
 */
class LayoutSwitcherStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val service = LayoutSwitcherHider.getInstance(project)
        ApplicationManager.getApplication().messageBus.connect(service)
            .subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { service.applyLater() })
        service.applyLater()
    }
}
