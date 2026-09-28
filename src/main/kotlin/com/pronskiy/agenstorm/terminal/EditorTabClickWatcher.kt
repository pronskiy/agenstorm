package com.pronskiy.agenstorm.terminal

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.terminal.TerminalMaximizeToggleAction.TerminalWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * leaves the terminal by the shortcut keeps a tab row that just switches tabs. That a tab click did is kept in
 * the project's workspace, so it outlasts a restart.
 *
 * The terminal comes back one double-click interval after the click, not at once: a double click on an editor
 * tab is the platform's Hide All Tool Windows, and its first click must not have maximized the terminal by the
 * time the second one arrives. Any press in that interval calls it off.
 *
 * It listens only while one of the two can happen: [watch] is called when the terminal is maximized, [resume]
 * when a project opens with the row armed, and the listener removes itself on the first click it sees once the
 * terminal is not maximized and the row is not armed, or once the feature is off.
 */
@Service(Service.Level.PROJECT)
class EditorTabClickWatcher(private val project: Project, private val scope: CoroutineScope) : Disposable {

    /** What a click on one of the project's editor tabs asks for. */
    enum class TabClick { EDITOR, TERMINAL, NONE }

    private var listening: Disposable? = null

    /** A tab click gave the editor back, so a click on the active tab asks for the terminal. J1.10. */
    private var armed: Boolean
        get() = PropertiesComponent.getInstance(project).getBoolean(ARMED_KEY)
        set(value) = PropertiesComponent.getInstance(project).setValue(ARMED_KEY, value)

    /** The tab label the last single press landed on, when its tab was the active one at that moment. */
    private var pressedActiveTab: Component? = null

    /** The terminal asked for by a click, waiting out the double-click interval. */
    private var pendingTerminal: Job? = null

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

    /** EDT, once the project is open: listens again if the row was armed when the project was last closed. */
    fun resume() {
        if (armed && AgenstormSettings.getInstance().state.terminalMaximizeEnabled) watch()
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
        // The second click of a double click, or a click anywhere else: the terminal is no longer what was asked.
        cancelPending()
        // Read before the press selects its tab. The second press of a double click is not asked.
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
                ApplicationManager.getApplication().invokeLater({
                    TerminalMaximizeToggleAction.maximizeEditor(project, terminal)
                }, ModalityState.nonModal(), project.disposed)
            }
            TabClick.TERMINAL -> pendingTerminal = scope.launch(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
                delay(UIUtil.getMultiClickInterval().toLong())
                pendingTerminal = null
                // Asked again: the interval is long enough for the terminal to have been shown some other way.
                val now = TerminalMaximizeToggleAction.stateOf(project, terminal)
                if (tabClick(now, armed, wasActive = true) == TabClick.TERMINAL) {
                    TerminalMaximizeToggleAction.maximizeTerminal(project, terminal)
                }
            }
            TabClick.NONE -> Unit
        }
    }

    private fun cancelPending() {
        pendingTerminal?.cancel()
        pendingTerminal = null
    }

    private fun stop() {
        pressedActiveTab = null
        cancelPending()
        val registration = listening ?: return
        listening = null
        Disposer.dispose(registration)
    }

    override fun dispose() {
        listening = null
        pressedActiveTab = null
        pendingTerminal = null
    }

    companion object {
        /** Workspace key: a tab click gave the editor back (J1.10). */
        private const val ARMED_KEY = "agenstorm.terminal.tabRowArmed"

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
