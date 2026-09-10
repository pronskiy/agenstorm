package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step J1.2. One key for "terminal, take the window", and the same key again for "give me the editor back".
 *
 * The platform can already maximize *the focused* tool window (`MaximizeToolWindow`, Ctrl+Shift+'), but only
 * once it is focused, and it acts on whatever that happens to be. This one always means the terminal, opens it
 * when it is closed, and its second press hides it and puts the caret back in the editor.
 *
 * Only the editor is squeezed: `setMaximized` leaves every other tool window where it is, and un-maximizing
 * restores the height the user dragged to, so no size has to be remembered here.
 *
 * The selected state is read from the platform on every update rather than stored — a remembered flag would
 * drift the moment someone drags the splitter or uses the platform's own maximize action.
 */
class TerminalMaximizeToggleAction : ToggleAction(), DumbAware {

    /** What a press should do, given where the terminal currently is. */
    enum class Step { MAXIMIZE_TERMINAL, MAXIMIZE_EDITOR, ACTIVATE_ONLY }

    /** The part of the tool window's state this action reasons about. */
    data class TerminalWindowState(val visible: Boolean, val maximized: Boolean, val docked: Boolean) {
        /** True when the terminal is the thing filling the window. */
        val isTerminalMaximized: Boolean get() = docked && visible && maximized
    }

    // BGT is safe here: getToolWindow and isMaximized asserts no thread, and update() touches no Swing of ours.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val on = AgenstormSettings.getInstance().state.terminalMaximizeEnabled
        val applicable = on && terminalOf(e.project) != null
        e.presentation.isEnabledAndVisible = applicable
        if (applicable) super.update(e)
    }

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        val terminal = terminalOf(project) ?: return false
        return stateOf(project, terminal).isTerminalMaximized
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        val terminal = terminalOf(project) ?: return
        when (nextStep(stateOf(project, terminal), wantMaximized = state)) {
            Step.MAXIMIZE_TERMINAL -> maximizeTerminal(project, terminal)
            Step.MAXIMIZE_EDITOR -> maximizeEditor(project, terminal)
            // Floating and windowed terminals are their own window already; there, setMaximized would maximize
            // that window rather than fill the IDE, which is not what the button says. Just bring it up.
            Step.ACTIVATE_ONLY -> terminal.activate(null, true, true)
        }
    }

    /** Show first and maximize once it is on screen: a hidden tool window has nothing to expand over. */
    private fun maximizeTerminal(project: Project, terminal: ToolWindow) {
        terminal.activate({
            if (!project.isDisposed) ToolWindowManager.getInstance(project).setMaximized(terminal, true)
        }, true, true)
    }

    private fun maximizeEditor(project: Project, terminal: ToolWindow) {
        val manager = ToolWindowManager.getInstance(project)
        // Un-maximize before hiding, so the height the user dragged to is what comes back next time.
        if (manager.isMaximized(terminal)) manager.setMaximized(terminal, false)
        terminal.hide(null)
        manager.activateEditorComponent()
    }

    companion object {
        /**
         * `TerminalToolWindowFactory.TOOL_WINDOW_ID`, spelled out. That constant inlines, but naming the class
         * here would tie this action to the Terminal plugin; as a literal it loads in IDEs without it and the
         * action simply finds no tool window. `TerminalMaximizeToggleActionTest` pins the two together.
         */
        const val TERMINAL_TOOL_WINDOW_ID = "Terminal"

        /** Pure: what a press does. [wantMaximized] is the state the toggle is being moved to. */
        fun nextStep(state: TerminalWindowState, wantMaximized: Boolean): Step = when {
            !state.docked -> Step.ACTIVATE_ONLY
            wantMaximized -> Step.MAXIMIZE_TERMINAL
            else -> Step.MAXIMIZE_EDITOR
        }

        fun stateOf(project: Project, terminal: ToolWindow): TerminalWindowState = TerminalWindowState(
            visible = terminal.isVisible,
            maximized = ToolWindowManager.getInstance(project).isMaximized(terminal),
            docked = terminal.type == ToolWindowType.DOCKED || terminal.type == ToolWindowType.SLIDING,
        )

        fun terminalOf(project: Project?): ToolWindow? {
            if (project == null || project.isDisposed) return null
            return ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
        }
    }
}
