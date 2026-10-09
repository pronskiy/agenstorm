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
 * Who changed it decides ([changed]). Opening, closing and moving the sidebar are the user's only on a click or a key in
 * the window in front; any other such change, in any window, is the platform's — a new project opening its Project view
 * on the same side closes ours, a project restores its saved layout — and the shared layout is put back. A width is
 * different: `stretchWidth` and the divider take effect at the next layout, after the click is over, so a width the
 * window in front settles at is taken as the user's — once that window has reached the shared width, not while one of
 * our own stretches is on its way. A window records nothing until it has taken the shared layout once ([apply]).
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

    /** Windows that took the shared layout and may record. */
    private val synced = HashSet<K>()

    /** Windows being changed by [apply] right now: the events that fires are ours. */
    private val applying = HashSet<K>()

    /** Windows whose width is still on its way to the shared one, with the checks queued: their width changes are ours. */
    private val widthPending = HashMap<K, Int>()

    /** The window [key]'s sidebar changed — during a click or a key press ([byUser]) or otherwise. */
    fun changed(key: K, byUser: Boolean) {
        if (!enabled() || key in applying) return
        if (key != front() || key !in synced) return apply(key)
        if (byUser) return record(key)
        val window = windows(key)?.takeIf { it.available } ?: return
        val shared = state.layout.value
        val current = window.layout()
        if (current.visible != shared.visible || current.split != shared.split || (shared.anchor.isNotEmpty() && current.anchor != shared.anchor)) return apply(key)
        if (key !in widthPending && SidebarSyncPlan.widthStep(shared, current) != null) record(key)
    }

    /** The window [key] changed its sidebar on the user's say: if it is the one in front, that is now everyone's. */
    fun record(key: K) {
        if (!enabled() || key != front() || key !in synced) return
        val window = windows(key)?.takeIf { it.available } ?: return
        if (state.update { SidebarSyncPlan.record(it, window.layout()) }) all().filter { it != key }.forEach(::apply)
    }

    /** Makes the window [key] show the shared layout. */
    fun apply(key: K) {
        if (!enabled() || key in applying) return
        val window = windows(key)?.takeIf { it.available } ?: return
        applying += key
        try {
            SidebarSyncPlan.steps(state.layout.value, window.layout()).forEach(window::run)
        } finally {
            applying -= key
        }
        synced += key
        widthLater(key, attempts = MAX_WAITS)
    }

    private fun widthLater(key: K, attempts: Int) {
        widthPending.merge(key, 1, Int::plus)
        later(key) {
            try {
                widthNow(key, attempts)
            } finally {
                widthPending.computeIfPresent(key) { _, n -> (n - 1).takeIf { it > 0 } }
            }
        }
    }

    private fun widthNow(key: K, attempts: Int) {
        val window = windows(key)?.takeIf { it.available } ?: return
        val current = window.layout()
        val from = stretchedFrom[key]
        if (from != null && current.width == from && attempts > 0) return widthLater(key, attempts - 1)
        stretchedFrom.remove(key)
        val step = SidebarSyncPlan.widthStep(state.layout.value, current) ?: return
        if (attempts <= 0) return
        stretchedFrom[key] = current.width
        applying += key
        try {
            window.run(step)
        } finally {
            applying -= key
        }
        widthLater(key, attempts - 1)
    }

    /** The window [key] is about to show something of its own (made available again): it records nothing until [apply]. */
    fun unsync(key: K) {
        synced.remove(key)
    }

    /** Forgets a window that is gone. */
    fun forget(key: K) {
        stretchedFrom.remove(key)
        synced.remove(key)
        widthPending.remove(key)
    }

    companion object {
        /** Turns a window may wait for its layout after a stretch, or stretch again, before it is left as it is. */
        const val MAX_WAITS: Int = 5
    }
}
