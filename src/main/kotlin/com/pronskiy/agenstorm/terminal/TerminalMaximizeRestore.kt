package com.pronskiy.agenstorm.terminal

import com.intellij.ide.ui.UISettings
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.ThreeComponentsSplitter
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.ComponentUtil
import com.intellij.ui.ExperimentalUI
import com.pronskiy.agenstorm.core.AgenstormSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Component
import kotlin.math.roundToInt

/**
 * Step J1.11. The terminal's own height survives being maximized, and a terminal maximized when the project
 * closes is maximized again when it opens.
 *
 * The platform keeps "maximized" in memory only — `ToolWindowPane`'s `maximizedProportion`, the pixel height to go
 * back to — and copies a tool window's size into its layout (`WindowInfo.weight`, what is saved and what the window
 * is shown at next time) from a resize listener debounced by 100 ms, which does nothing for a window no longer
 * showing. Maximizing is a resize like any other, so the stretched height is recorded. Un-maximizing and hiding in
 * the same moment — the toggle's second press, a tab click, a double click taken back — is not: the window is gone
 * before the listener runs. So the terminal came back full height the next time it was shown, by any means, and a
 * project closed with it maximized reopened it stretched but not maximized, with the dragged-to height lost. The
 * layout itself is `@ApiStatus.Internal` (`ToolWindowManagerEx.getLayout`, `DesktopLayout`), so it is not written.
 *
 * Instead, at the moments the platform misses, the height un-maximizing gave back is measured — as a share of the
 * splitter the terminal sits in, so a differently sized window next time still gets the right proportion — and kept
 * in the workspace. The next time the terminal is shown, it is set back to that height with `stretchHeight` and the
 * record is dropped: from then on the platform's own listener has the right size again.
 *
 * Unless "Remember size for each tool window" is on, the bottom tool windows also share one height (the layout's
 * `unified_weights`), which each of them takes when it is activated — so the stretched height reached Run, Problems
 * and the rest too. The terminal's own weight and that shared one are both stale, and either can be the next one
 * used, so the height is kept twice: once for the terminal, which a plain `show` sizes from its own weight, and
 * once for whichever tool window at its side is shown next. Setting the terminal right puts the shared height
 * right too; setting another window right leaves the terminal's own record for when it is shown.
 *
 * A layout stretched by an earlier version gives nothing back to measure: un-maximizing returns (almost) the full
 * height, because that is what maximizing recorded. Then a third of the splitter is kept instead of the stretched
 * height, near the platform's own default for a new bottom tool window.
 *
 * A project closed with the terminal maximized also records that, and maximizes it again on open, through the same
 * call and so from the same height. The tab row's arming (J1.10) comes back the same way, through
 * [EditorTabClickWatcher.resume].
 */
object TerminalMaximizeRestore {

    private val LOG = logger<TerminalMaximizeRestore>()

    /** Workspace key: the terminal was maximized when the project was last closed. */
    const val MAXIMIZED_KEY = "agenstorm.terminal.maximizedOnClose"

    /** Workspace key: the terminal's own height, as a share of its splitter, while the platform's is the stretched one. */
    const val TERMINAL_HEIGHT_KEY = "agenstorm.terminal.ownHeightShare"

    /** Workspace key: the same height, for the next tool window shown at the terminal's side while sizes are shared. */
    const val SHARED_HEIGHT_KEY = "agenstorm.terminal.sharedHeightShare"

    /** EDT. The toggle's way out of a maximized terminal: back to its own height, kept, then hidden. */
    fun unmaximizeAndHide(project: Project, terminal: ToolWindow) {
        val manager = ToolWindowManager.getInstance(project)
        if (manager.isMaximized(terminal)) {
            val maximizedHeight = splitterAndArea(terminal)?.second?.height ?: 0
            manager.setMaximized(terminal, false)
            keepOwnHeight(project, terminal, maximizedHeight)
        }
        terminal.hide(null)
    }

    /** EDT, before the project's layout is saved. */
    fun beforeSave(project: Project) {
        val terminal = TerminalMaximizeToggleAction.terminalOf(project) ?: return
        val maximized = AgenstormSettings.getInstance().state.terminalMaximizeEnabled &&
            TerminalMaximizeToggleAction.stateOf(project, terminal).isTerminalMaximized
        PropertiesComponent.getInstance(project).setValue(MAXIMIZED_KEY, maximized)
        if (!maximized) return
        val maximizedHeight = splitterAndArea(terminal)?.second?.height ?: 0
        ToolWindowManager.getInstance(project).setMaximized(terminal, false)
        keepOwnHeight(project, terminal, maximizedHeight)
    }

    /** Whether the terminal was maximized when [project] was last closed; asking forgets it. */
    fun takeMaximizedOnClose(project: Project): Boolean {
        val properties = PropertiesComponent.getInstance(project)
        return properties.getBoolean(MAXIMIZED_KEY).also { properties.unsetValue(MAXIMIZED_KEY) }
    }

    /**
     * EDT. Sets a shown, un-maximized [toolWindow] — the terminal, or another one sharing its height — back to the
     * height kept for it, and forgets it; unless the pane has not been laid out yet, in which case it is kept for
     * the next time this is asked.
     */
    fun restoreOwnHeight(project: Project, toolWindow: ToolWindow) {
        val isTerminal = toolWindow.id == TerminalMaximizeToggleAction.TERMINAL_TOOL_WINDOW_ID
        val key = if (isTerminal) TERMINAL_HEIGHT_KEY else SHARED_HEIGHT_KEY
        val properties = PropertiesComponent.getInstance(project)
        val share = parseShare(properties.getValue(key)) ?: return
        val id = toolWindow.id
        val maximized = ToolWindowManager.getInstance(project).isMaximized(toolWindow)
        if (!toolWindow.isVisible || maximized) {
            if (LOG.isDebugEnabled) LOG.debug("own height: not now for $id, share=$share visible=${toolWindow.isVisible} maximized=$maximized")
            return
        }
        WindowManager.getInstance().getFrame(project)?.validate()
        val (splitter, area) = splitterAndArea(toolWindow) ?: run {
            if (LOG.isDebugEnabled) LOG.debug("own height: no splitter yet for $id, share=$share")
            return
        }
        if (splitter.height <= 0 || area.height <= 0) {
            if (LOG.isDebugEnabled) LOG.debug("own height: not laid out for $id, share=$share splitter=${splitter.height} area=${area.height}")
            return
        }
        // The terminal's resize reaches the shared height as well; another window's leaves the terminal's own.
        properties.unsetValue(SHARED_HEIGHT_KEY)
        if (isTerminal) properties.unsetValue(TERMINAL_HEIGHT_KEY)
        val before = area.height
        val delta = stretchBy(share, splitter.height, before)
        if (delta != 0) {
            (toolWindow as? ToolWindowEx)?.stretchHeight(delta)
            WindowManager.getInstance().getFrame(project)?.validate()
        }
        if (LOG.isDebugEnabled) LOG.debug("own height: restored $id share=$share splitter=${splitter.height} area=$before delta=$delta -> ${area.height}")
    }

    /**
     * Measures the height un-maximizing just gave back, while the terminal is still laid out at it. Heights only: a
     * terminal at a side is sized by its width, and nothing here measures that.
     */
    private fun keepOwnHeight(project: Project, terminal: ToolWindow, maximizedHeight: Int) {
        if (!terminal.anchor.isHorizontal) return
        WindowManager.getInstance().getFrame(project)?.validate()
        val (splitter, area) = splitterAndArea(terminal) ?: run {
            if (LOG.isDebugEnabled) LOG.debug("own height: nothing to measure")
            return
        }
        if (splitter.height <= 0 || area.height <= 0) return
        val share = ownShare(area.height, splitter.height, maximizedHeight).toString()
        val properties = PropertiesComponent.getInstance(project)
        properties.setValue(TERMINAL_HEIGHT_KEY, share)
        properties.setValue(SHARED_HEIGHT_KEY, share)
        if (LOG.isDebugEnabled) {
            LOG.debug("own height: kept share=$share splitter=${splitter.height} area=${area.height} maximized=$maximizedHeight")
        }
    }

    /** Whether the terminal's kept height goes to [toolWindow] when it is shown. */
    fun receivesOwnHeight(project: Project, toolWindow: ToolWindow): Boolean {
        val terminal = TerminalMaximizeToggleAction.terminalOf(project) ?: return false
        val ui = UISettings.getInstance()
        val sizesShared = !(if (ExperimentalUI.isNewUI()) ui.rememberSizeForEachToolWindowNewUI else ui.rememberSizeForEachToolWindowOldUI)
        return receivesOwnHeight(
            isTerminal = toolWindow.id == terminal.id,
            sizesShared = sizesShared,
            sameSide = toolWindow.anchor == terminal.anchor,
            docked = toolWindow.type == ToolWindowType.DOCKED,
        )
    }

    /**
     * The pane's splitter a tool window is in, and the child of it that holds the tool window — its decorator, or
     * the splitter that shares the side between it and a neighbour. That child is what `stretchHeight` resizes.
     */
    private fun splitterAndArea(toolWindow: ToolWindow): Pair<ThreeComponentsSplitter, Component>? {
        val content = toolWindow.component
        val splitter = ComponentUtil.getParentOfType(ThreeComponentsSplitter::class.java, content) ?: return null
        var area: Component = content
        while (area.parent !== splitter) area = area.parent ?: return null
        return splitter to area
    }

    /**
     * Pure: the terminal's own share of the splitter, from the height un-maximizing gave back. Almost the full
     * [maximized] height back means maximizing had recorded a stretched one, so a third is used instead.
     */
    fun ownShare(restored: Int, total: Int, maximized: Int): Float =
        if (maximized > 0 && restored >= maximized * LOST_RATIO) FALLBACK_SHARE else restored.toFloat() / total

    /**
     * Pure: the terminal takes its kept height back when shown, and so does another docked tool window at its side
     * while the platform gives the tool windows of a side one shared height.
     */
    fun receivesOwnHeight(isTerminal: Boolean, sizesShared: Boolean, sameSide: Boolean, docked: Boolean): Boolean =
        isTerminal || (sizesShared && sameSide && docked)

    private const val LOST_RATIO = 0.9f
    private const val FALLBACK_SHARE = 1f / 3

    /** Pure: a share read back from the workspace, or null when it is missing or not a share of anything. */
    fun parseShare(text: String?): Float? = text?.toFloatOrNull()?.takeIf { it > 0f && it < 1f }

    /** Pure: how many pixels to stretch a [current]-high area by to make it [share] of [total]. */
    fun stretchBy(share: Float, total: Int, current: Int): Int = (share * total).roundToInt() - current

    /** Pure: the terminal is on screen at a height that has stopped changing — the frame may still be growing. */
    fun isSettled(visible: Boolean, showing: Boolean, height: Int, previousHeight: Int): Boolean =
        visible && showing && height > 0 && height == previousHeight
}

/** Step J1.11, registered under `applicationListeners`. */
class TerminalMaximizeCloseListener : ProjectCloseListener {

    override fun projectClosingBeforeSave(project: Project) {
        TerminalMaximizeRestore.beforeSave(project)
    }
}

/**
 * Step J1.11, registered under `projectListeners`: the terminal shown by any means gets its own height back, and
 * so does the first tool window shown at its side while they share one height.
 */
class TerminalShownListener(private val project: Project) : ToolWindowManagerListener {

    override fun toolWindowShown(toolWindow: ToolWindow) {
        if (!TerminalMaximizeRestore.receivesOwnHeight(project, toolWindow)) return
        TerminalMaximizeRestore.restoreOwnHeight(project, toolWindow)
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
        // The layout brings the terminal back; wait for it to be on screen and laid out.
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
