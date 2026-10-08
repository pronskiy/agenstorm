package com.pronskiy.agenstorm.terminal.agents

/** One window's Agents tool window, as [SidebarSyncer] sees it. */
interface SidebarWindow {
    /** Shown in the window's tool window bars at all: false while the setting is off. */
    val available: Boolean

    fun layout(): FrameLayout

    fun run(step: SidebarStep)
}

/**
 * Step X1.4. Records the sidebar from the window in front and applies it to every other window at once (decisions 102,
 * 105). Keyed by whatever names a window — a project in the IDE, a string in the tests — and run on one thread, the EDT.
 *
 * The width goes last and once: after the side and visibility, [later] asks again for the width the window has laid out
 * to, and while a stretch has not reached the layout yet (the window still at the width it was stretched from) it waits
 * for another turn rather than stretch twice — X0.1's spike overshot exactly that way.
 */
class SidebarSyncer<K : Any>(
    private val state: AgentsSidebarState,
    private val windows: (K) -> SidebarWindow?,
    private val front: () -> K?,
    private val all: () -> List<K>,
    private val enabled: () -> Boolean,
    private val later: (K, () -> Unit) -> Unit,
) {
    /** Width a window had when it was last stretched, until its layout catches up. */
    private val stretchedFrom = HashMap<K, Int>()

    /** The window [key] may have changed its sidebar; if it is the one in front, that is now everyone's. */
    fun record(key: K) {
        if (!enabled() || key != front()) return
        val window = windows(key)?.takeIf { it.available } ?: return
        if (state.update { SidebarSyncPlan.record(it, window.layout()) }) all().filter { it != key }.forEach(::apply)
    }

    /** Makes the window [key] show the shared layout. */
    fun apply(key: K) {
        if (!enabled()) return
        val window = windows(key)?.takeIf { it.available } ?: return
        SidebarSyncPlan.steps(state.layout.value, window.layout()).forEach(window::run)
        widthLater(key, attempts = MAX_WAITS)
    }

    private fun widthLater(key: K, attempts: Int) {
        later(key) {
            val window = windows(key)?.takeIf { it.available }
            if (window != null) {
                val current = window.layout()
                val from = stretchedFrom[key]
                if (from != null && current.width == from && attempts > 0) {
                    widthLater(key, attempts - 1)
                } else {
                    stretchedFrom.remove(key)
                    SidebarSyncPlan.widthStep(state.layout.value, current)?.let { step ->
                        stretchedFrom[key] = current.width
                        window.run(step)
                    }
                }
            }
        }
    }

    /** Forgets a window that is gone. */
    fun forget(key: K) {
        stretchedFrom.remove(key)
    }

    companion object {
        /** Turns a window may wait for its layout after a stretch before it is asked again regardless. */
        const val MAX_WAITS: Int = 5
    }
}
