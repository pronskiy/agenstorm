package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.terminal.TerminalMaximizeToggleAction.TerminalWindowState
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * Steps J1.9 and J1.10. The editor tab row as the other half of the terminal toggle.
 *
 * While the terminal fills the editor's area, the editor tab row is all that shows of the editor, and a click on
 * one of those tabs asks for that file: the terminal gives the editor back, as the toggle's second press does.
 * The click itself goes on to select the tab as usual (J1.9).
 *
 * Once a tab click has given the editor back, the row works the other way too: a click on the tab that was
 * already the active one asks for the terminal again, as the toggle's first press does, and a click on any other
 * tab only switches to it (J1.10). "Already" is decided when the mouse goes down, because the press is what
 * selects a tab. Only while the terminal is hidden: one shown at its normal size is being used beside the editor,
 * and a click on the active tab is how the caret gets back to the file. And only after a tab click — whoever
 * leaves the terminal by the shortcut keeps a tab row that just switches tabs.
 *
 * It listens only while one of the two can happen: [watch] is called when the terminal is maximized, and the
 * listener removes itself on the first click it sees once the terminal is not maximized and no tab click has
 * given the editor back, or once the feature is off.
 */
@Service(Service.Level.PROJECT)
class EditorTabClickWatcher(private val project: Project) : Disposable {

    /** What a click on one of the project's editor tabs asks for. */
    enum class TabClick { EDITOR, TERMINAL, NONE }

    private var listening: Disposable? = null

    /** A tab click gave the editor back, so a click on the active tab asks for the terminal. J1.10. */
    private var armed = false

    /** The tab label the last single press landed on, when its tab was the active one at that moment. */
    private var pressedActiveTab: Component? = null

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
        if (e.id != MouseEvent.MOUSE_PRESSED && e.id != MouseEvent.MOUSE_CLICKED) return
        if (!SwingUtilities.isLeftMouseButton(e) || project.isDisposed) return
        val terminal = TerminalMaximizeToggleAction.terminalOf(project)
        if (terminal == null || !AgenstormSettings.getInstance().state.terminalMaximizeEnabled) {
            armed = false
            stop()
            return
        }
        val state = TerminalMaximizeToggleAction.stateOf(project, terminal)
        if (!state.isTerminalMaximized && !armed) {
            stop()
            return
        }
        if (e.id == MouseEvent.MOUSE_PRESSED) pressed(e) else clicked(e, state, terminal)
    }

    private fun pressed(e: MouseEvent) {
        // Read before the press selects its tab. The second press of a double click is not asked: by then the
        // first click has acted, and its tab is the active one.
        pressedActiveTab = e.component.takeIf { e.clickCount == 1 && EditorTabRow.isActiveEditorTab(project, it) }
    }

    private fun clicked(e: MouseEvent, state: TerminalWindowState, terminal: ToolWindow) {
        val wasActive = pressedActiveTab != null && pressedActiveTab === e.component
        pressedActiveTab = null
        if (!EditorTabRow.isEditorTabClick(project, e.component)) return
        when (tabClick(state, armed, wasActive)) {
            TabClick.EDITOR -> {
                armed = true
                // After the click has selected its tab, so the editor that gets the caret is the one clicked.
                later { TerminalMaximizeToggleAction.maximizeEditor(project, terminal) }
            }
            TabClick.TERMINAL -> later { TerminalMaximizeToggleAction.maximizeTerminal(project, terminal) }
            TabClick.NONE -> Unit
        }
    }

    private fun later(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block, ModalityState.nonModal(), project.disposed)
    }

    private fun stop() {
        pressedActiveTab = null
        val registration = listening ?: return
        listening = null
        Disposer.dispose(registration)
    }

    override fun dispose() {
        listening = null
        pressedActiveTab = null
    }

    companion object {
        /**
         * Pure: what a click on one of the editor tabs asks for. [armed]: a tab click has given the editor back
         * before. [wasActive]: the clicked tab was the active one before the click selected it.
         */
        fun tabClick(terminal: TerminalWindowState, armed: Boolean, wasActive: Boolean): TabClick = when {
            terminal.isTerminalMaximized -> TabClick.EDITOR
            armed && wasActive && terminal.docked && !terminal.visible -> TabClick.TERMINAL
            else -> TabClick.NONE
        }
    }
}
