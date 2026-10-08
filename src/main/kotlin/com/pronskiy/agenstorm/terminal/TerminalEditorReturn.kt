package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.terminal.TerminalMaximizeToggleAction.TerminalWindowState
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import javax.swing.SwingUtilities

/**
 * Step J1.12. A file you open while the terminal fills the editor's area gives the editor back, as a click on its tab
 * does (J1.9): the terminal un-maximized and hidden, the caret in the file, and the tab row armed, so a click on the
 * active tab brings the terminal back (J1.10).
 *
 * "You open" is two things. A file editor taking the keyboard: a path clicked in the terminal — the file already
 * shown included, whose tab does not change — `open`, `$EDITOR`, Navigate, Recent Files, a Project view double click
 * or Enter. And the selected file changing while the keyboard is in the window outside the terminal: a single click
 * in the Project view, which opens a file without moving the keyboard. A file an agent opens in the background while
 * you type in the terminal is neither, by Roman's choice. The focus trigger waits out [GRACE_MS] after the terminal
 * was maximized, so the focus J1.10's click on the active tab gives the editor does not undo that very click.
 *
 * It listens only while the terminal is maximized: [watch] is called when it is, and the listeners go on the first
 * event that finds it no longer is, or the feature off.
 */
@Service(Service.Level.PROJECT)
class TerminalEditorReturn(private val project: Project) : Disposable {

    /** What happened in the editor. */
    enum class Trigger { EDITOR_FOCUSED, SELECTION_CHANGED }

    /** Where the keyboard is when it happened. */
    enum class Focus { EDITOR, TERMINAL, ELSEWHERE_IN_WINDOW, NONE }

    private var listening: Disposable? = null

    @Volatile
    private var maximizedAt = 0L

    /** EDT, from [TerminalMaximizeToggleAction.afterMaximized]: the terminal was just maximized. */
    fun watch() {
        maximizedAt = System.currentTimeMillis()
        if (listening != null) return
        val lifetime = Disposer.newDisposable(this, "Agenstorm J1.12")
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val focusListener = PropertyChangeListener { event -> (event.newValue as? Component)?.let(::focusMoved) }
        focusManager.addPropertyChangeListener(FOCUS_OWNER, focusListener)
        Disposer.register(lifetime, Disposable { focusManager.removePropertyChangeListener(FOCUS_OWNER, focusListener) })
        project.messageBus.connect(lifetime).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                if (event.newFile != null && event.newFile != event.oldFile) consider(Trigger.SELECTION_CHANGED, focusNow())
            }
        })
        listening = lifetime
    }

    private fun focusMoved(owner: Component) {
        if (isInFileEditor(owner)) consider(Trigger.EDITOR_FOCUSED, Focus.EDITOR)
    }

    private fun consider(trigger: Trigger, focus: Focus) {
        if (project.isDisposed) return
        val enabled = AgenstormSettings.getInstance().state.terminalMaximizeEnabled
        val terminal = TerminalMaximizeToggleAction.terminalOf(project)
        val state = terminal?.let { TerminalMaximizeToggleAction.stateOf(project, it) }
        if (terminal == null || state == null || !enabled || !state.isTerminalMaximized) {
            stop()
            return
        }
        if (!givesEditorBack(state, enabled, trigger, focus, System.currentTimeMillis() - maximizedAt)) return
        stop()
        project.service<EditorTabClickWatcher>().arm()
        // After the open has done its own work, and only if nothing gave the editor back meanwhile — a click on a tab
        // is both a selection change here and J1.9's own click.
        ApplicationManager.getApplication().invokeLater({
            if (TerminalMaximizeToggleAction.stateOf(project, terminal).isTerminalMaximized) {
                TerminalMaximizeToggleAction.maximizeEditor(project, terminal)
            }
        }, ModalityState.nonModal(), project.disposed)
    }

    private fun focusNow(): Focus {
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().permanentFocusOwner ?: return Focus.NONE
        if (isInFileEditor(owner)) return Focus.EDITOR
        val terminal = TerminalMaximizeToggleAction.terminalOf(project)
        if (terminal != null && SwingUtilities.isDescendingFrom(owner, terminal.component)) return Focus.TERMINAL
        val frame = WindowManager.getInstance().getFrame(project) ?: return Focus.NONE
        return if (SwingUtilities.isDescendingFrom(owner, frame)) Focus.ELSEWHERE_IN_WINDOW else Focus.NONE
    }

    private fun isInFileEditor(owner: Component): Boolean =
        !project.isDisposed && FileEditorManager.getInstance(project).selectedEditors.any { SwingUtilities.isDescendingFrom(owner, it.component) }

    private fun stop() {
        val lifetime = listening ?: return
        listening = null
        Disposer.dispose(lifetime)
    }

    override fun dispose() {
        listening = null
    }

    companion object {
        /** After the terminal was maximized, the editor taking the keyboard is the maximizing itself, not an open. */
        const val GRACE_MS = 500L

        private const val FOCUS_OWNER = "permanentFocusOwner"

        /** Pure: whether [trigger], with the keyboard at [focus], gives the editor back from [terminal]. */
        fun givesEditorBack(terminal: TerminalWindowState, enabled: Boolean, trigger: Trigger, focus: Focus, msSinceMaximized: Long): Boolean {
            if (!enabled || !terminal.isTerminalMaximized) return false
            return when (trigger) {
                Trigger.EDITOR_FOCUSED -> msSinceMaximized >= GRACE_MS
                Trigger.SELECTION_CHANGED -> focus == Focus.EDITOR || focus == Focus.ELSEWHERE_IN_WINDOW
            }
        }
    }
}
