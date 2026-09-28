package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * Step J1.9. While the terminal fills the editor's area, the editor tab row is all that shows of the editor,
 * and a click on one of those tabs asks for that file: the terminal gives the editor back, as the toggle's
 * second press does. The click itself goes on to select the tab as usual.
 *
 * It listens only while the terminal is maximized: [watch] is called when it is, and the listener removes
 * itself on the first mouse click it sees after the terminal stopped being maximized, however that happened.
 */
@Service(Service.Level.PROJECT)
class EditorTabClickWatcher(private val project: Project) : Disposable {

    private var listening: Disposable? = null

    /** EDT. Starts listening, once. */
    fun watch() {
        if (listening != null) return
        val toolkit = Toolkit.getDefaultToolkit()
        val listener = AWTEventListener { event -> (event as? MouseEvent)?.let(::mouseEvent) }
        toolkit.addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        val registration = Disposable { toolkit.removeAWTEventListener(listener) }
        Disposer.register(this, registration)
        listening = registration
    }

    private fun mouseEvent(e: MouseEvent) {
        if (e.id != MouseEvent.MOUSE_CLICKED || !SwingUtilities.isLeftMouseButton(e) || project.isDisposed) return
        val terminal = TerminalMaximizeToggleAction.terminalOf(project)
        if (terminal == null || !TerminalMaximizeToggleAction.stateOf(project, terminal).isTerminalMaximized) {
            stop()
            return
        }
        if (!EditorTabRow.isEditorTabClick(project, e.component)) return
        stop()
        // After the click has selected its tab, so the editor that gets the caret is the one clicked.
        ApplicationManager.getApplication().invokeLater({
            TerminalMaximizeToggleAction.maximizeEditor(project, terminal)
        }, ModalityState.nonModal(), project.disposed)
    }

    private fun stop() {
        val registration = listening ?: return
        listening = null
        Disposer.dispose(registration)
    }

    override fun dispose() {
        listening = null
    }
}
