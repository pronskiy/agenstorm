package com.pronskiy.agenstorm.terminal

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.pronskiy.agenstorm.core.AgenstormSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Step J1.11. A terminal maximized when the project closes is maximized again when it opens.
 *
 * The platform keeps "maximized" in memory only — `ToolWindowPane`'s `maximizedProportion`, which holds the height
 * to go back to — while the stretched height is what the layout saves as the terminal's own. So a project closed
 * with the terminal maximized came back with a terminal that looked maximized and was not: the toggle read it as
 * normal, the tab row did nothing, and the height the user had dragged to was gone.
 *
 * So the terminal is un-maximized before the layout is saved (`projectClosingBeforeSave`, which the platform fires
 * ahead of the save both when a project closes and when the IDE quits), and that it was maximized goes into the
 * project's workspace. On the next open it is maximized again once it is on screen. The tab row's arming (J1.10)
 * comes back the same way, through [EditorTabClickWatcher.resume].
 */
object TerminalMaximizeRestore {

    /** Workspace key: the terminal was maximized when the project was last closed. */
    const val MAXIMIZED_KEY = "agenstorm.terminal.maximizedOnClose"

    /** EDT, before the project's layout is saved. */
    fun beforeSave(project: Project) {
        val terminal = TerminalMaximizeToggleAction.terminalOf(project) ?: return
        val maximized = AgenstormSettings.getInstance().state.terminalMaximizeEnabled &&
            TerminalMaximizeToggleAction.stateOf(project, terminal).isTerminalMaximized
        PropertiesComponent.getInstance(project).setValue(MAXIMIZED_KEY, maximized)
        // Puts the height the user dragged to back into the layout that is about to be saved.
        if (maximized) ToolWindowManager.getInstance(project).setMaximized(terminal, false)
    }

    /** Whether the terminal was maximized when [project] was last closed; asking forgets it. */
    fun takeMaximizedOnClose(project: Project): Boolean {
        val properties = PropertiesComponent.getInstance(project)
        return properties.getBoolean(MAXIMIZED_KEY).also { properties.unsetValue(MAXIMIZED_KEY) }
    }

    /**
     * Pure: the terminal is on screen at a height that has stopped changing. `setMaximized` records the height to
     * come back to in pixels, and a frame still growing to its size would hand it the wrong one.
     */
    fun isSettled(visible: Boolean, showing: Boolean, height: Int, previousHeight: Int): Boolean =
        visible && showing && height > 0 && height == previousHeight
}

/** Step J1.11, registered under `applicationListeners`. */
class TerminalMaximizeCloseListener : ProjectCloseListener {

    override fun projectClosingBeforeSave(project: Project) {
        TerminalMaximizeRestore.beforeSave(project)
    }
}

/** Steps J1.10 and J1.11: the tab row listens again, and the terminal is maximized again. `postStartupActivity`. */
class TerminalMaximizeStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val wasMaximized = TerminalMaximizeRestore.takeMaximizedOnClose(project)
        val edt = Dispatchers.EDT + ModalityState.nonModal().asContextElement()
        withContext(edt) {
            if (!project.isDisposed) project.service<EditorTabClickWatcher>().resume()
        }
        if (!wasMaximized || !AgenstormSettings.getInstance().state.terminalMaximizeEnabled) return
        // The layout brings the terminal back at its own height; wait for it to be on screen and laid out.
        var previousHeight = -1
        repeat(ATTEMPTS) {
            val done = withContext(edt) {
                if (project.isDisposed) return@withContext true
                val terminal = TerminalMaximizeToggleAction.terminalOf(project) ?: return@withContext false
                val component = terminal.component
                val height = component.height
                val settled = TerminalMaximizeRestore.isSettled(terminal.isVisible, component.isShowing, height, previousHeight)
                previousHeight = height
                if (!settled) return@withContext false
                if (!ToolWindowManager.getInstance(project).isMaximized(terminal)) {
                    TerminalMaximizeToggleAction.maximizeTerminal(project, terminal, changeLayout = false)
                }
                true
            }
            if (done) return
            delay(POLL_MS)
        }
    }

    private companion object {
        const val POLL_MS = 100L

        /** Ten seconds: a terminal not on screen by then is one the layout did not bring back. */
        const val ATTEMPTS = 100
    }
}
