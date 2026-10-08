package com.pronskiy.agenstorm.terminal.agents

/**
 * Step X1.3. What one window's Agents tool window looks like now: open or not, its width as last laid out (0 when it
 * never was), its side.
 */
data class FrameLayout(val visible: Boolean, val width: Int, val anchor: String)

/** One change to one window's Agents tool window. */
sealed interface SidebarStep {
    data class SetAnchor(val anchor: String) : SidebarStep
    data object Show : SidebarStep
    data object Hide : SidebarStep

    /** `ToolWindowEx.stretchWidth`: a delta, taken at the next layout (X0.1). */
    data class Stretch(val delta: Int) : SidebarStep
}

/**
 * Step X1.3. Pure: the steps that make one window's sidebar match the shared [AgentsSidebarState.Layout], and the
 * layout recorded from the window in front. The side and visibility go first ([steps]); the width only once the window
 * has laid out after them ([widthStep]), because `stretchWidth` is a delta from the width it has then — applied once
 * per change, or it overshoots (X0.1).
 */
object SidebarSyncPlan {

    /** Widths this close are the same: a stretch is turned into proportions and back, and may land a pixel off. */
    const val WIDTH_TOLERANCE: Int = 2

    fun steps(shared: AgentsSidebarState.Layout, current: FrameLayout): List<SidebarStep> = buildList {
        if (shared.anchor.isNotEmpty() && shared.anchor != current.anchor) add(SidebarStep.SetAnchor(shared.anchor))
        if (shared.visible && !current.visible) add(SidebarStep.Show)
        if (!shared.visible && current.visible) add(SidebarStep.Hide)
    }

    fun widthStep(shared: AgentsSidebarState.Layout, current: FrameLayout): SidebarStep.Stretch? {
        if (!shared.visible || !current.visible || shared.width <= 0 || current.width <= 0) return null
        if (shared.anchor.isNotEmpty() && shared.anchor != current.anchor) return null
        val delta = shared.width - current.width
        return if (kotlin.math.abs(delta) <= WIDTH_TOLERANCE) null else SidebarStep.Stretch(delta)
    }

    /** The shared layout after the window in front showed [current]; a hidden or never laid out window keeps the old width. */
    fun record(shared: AgentsSidebarState.Layout, current: FrameLayout): AgentsSidebarState.Layout =
        shared.copy(
            visible = current.visible,
            anchor = current.anchor,
            width = if (current.visible && current.width > 0) current.width else shared.width,
        )
}
