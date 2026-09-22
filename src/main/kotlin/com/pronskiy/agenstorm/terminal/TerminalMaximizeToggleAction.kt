package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.pronskiy.agenstorm.core.AgenstormSettings
import java.awt.KeyboardFocusManager

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
        val step = nextStep(stateOf(project, terminal), wantMaximized = state)
        if (LOG.isDebugEnabled) LOG.debug("toggle: step=$step, focus=${focusOwner()}")
        // Not here and now, one event later. This action reshapes the tool window pane — it hides the
        // terminal, and on its first use it re-nests the pane's splitters — and the terminal's title bar,
        // this button's own toolbar included, is rebuilt when that happens. `ActionButton.performAction`
        // refreshes that toolbar the moment the action returns, and `ActionToolbarImpl` warns, with the
        // toolbar's creation trace attached, when it is asked to update a toolbar that no longer has a
        // parent. Letting the button finish first costs nothing visible and keeps the log clean. J2.5.
        ToolWindowManager.getInstance(project).invokeLater {
            if (project.isDisposed) return@invokeLater
            when (step) {
                Step.MAXIMIZE_TERMINAL -> maximizeTerminal(project, terminal)
                Step.MAXIMIZE_EDITOR -> maximizeEditor(project, terminal)
                // Floating and windowed terminals are their own window already; there, setMaximized would
                // maximize that window rather than fill the IDE, which is not what the button says. Just
                // bring it up.
                Step.ACTIVATE_ONLY -> terminal.activate(null, true, true)
            }
        }
    }

    /** Show first and maximize once it is on screen: a hidden tool window has nothing to expand over. */
    private fun maximizeTerminal(project: Project, terminal: ToolWindow) {
        // Give the editor its own column first, or "maximized" would mean the whole window, side tool windows
        // included — that is the pane's geometry, not something setMaximized can choose. See J2.1.
        TerminalMaximizeLayout.ensureEditorAreaOnly(project)
        terminal.activate({
            if (project.isDisposed) return@activate
            ToolWindowManager.getInstance(project).setMaximized(terminal, true)
            if (LOG.isDebugEnabled) LOG.debug("maximize: pane reshaped, active=${terminal.isActive}, focus=${focusOwner()}")
            focusTerminal(project, terminal)
        }, true, true)
    }

    /**
     * `activate` asks for the focus before the pane is reshaped; ask once more one event later, through the
     * content manager — the same call the Terminal plugin makes for a tab it opens itself
     * (`setSelectedContent(content, requestFocus = true)`). J1.7.
     */
    private fun focusTerminal(project: Project, terminal: ToolWindow) {
        afterFocusSettles(project) {
            val contentManager = terminal.contentManagerIfCreated
            val content = contentManager?.selectedContent
            if (LOG.isDebugEnabled) {
                LOG.debug(
                    "maximize: focus settled, focus=${focusOwner()}, tab=${content?.displayName}, " +
                        "target=${content?.preferredFocusableComponent?.let { "${it.javaClass.name} showing=${it.isShowing}" }}",
                )
            }
            if (content == null) return@afterFocusSettles
            contentManager.requestFocus(content, true)
            afterFocusSettles(project) {
                if (LOG.isDebugEnabled) LOG.debug("maximize: after re-request, active=${terminal.isActive}, focus=${focusOwner()}")
            }
        }
    }

    /**
     * One EDT event later, under the same modality the toggle runs in. A focus request posts its events to the
     * queue right away, so a runnable posted after it runs once they are dispatched. (`IdeFocusManager`'s
     * `doWhenFocusSettlesDown` is deprecated in 2026.2 and the verifier counts it.)
     */
    private fun afterFocusSettles(project: Project, block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block, ModalityState.nonModal(), project.disposed)
    }

    private fun maximizeEditor(project: Project, terminal: ToolWindow) {
        val manager = ToolWindowManager.getInstance(project)
        // Un-maximize before hiding, so the height the user dragged to is what comes back next time.
        if (manager.isMaximized(terminal)) manager.setMaximized(terminal, false)
        terminal.hide(null)
        manager.activateEditorComponent()
        afterFocusSettles(project) {
            if (LOG.isDebugEnabled) LOG.debug("editor: focus settled, focus=${focusOwner()}")
        }
    }

    companion object {
        private val LOG = logger<TerminalMaximizeToggleAction>()

        /** What `plugin.xml` registers this action as; [TerminalMaximizeShortcutPromoter] reads its binding. */
        const val ACTION_ID = "Agenstorm.ToggleTerminalMaximized"

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

        /** For the debug trace: who has the keyboard right now. */
        private fun focusOwner(): String? =
            KeyboardFocusManager.getCurrentKeyboardFocusManager().permanentFocusOwner?.toString()
    }
}
