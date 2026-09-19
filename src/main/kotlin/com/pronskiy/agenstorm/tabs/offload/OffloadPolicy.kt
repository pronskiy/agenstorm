package com.pronskiy.agenstorm.tabs.offload

/**
 * Step P2.3. The one rule of offloading, with nothing of the platform in it: given the loaded projects, which of
 * them go, in which order. Idle ones first — every candidate whose window has not been active for [idleMs] —
 * then, while more than [maxLoaded] would stay, the least recently active of the rest. Three projects never go:
 * the active one, a busy one (a terminal command or a process running, see [OffloadGuard]) and the last loaded
 * one, because closing it would leave the IDE on the welcome screen. A project with no recorded activity counts
 * as active now.
 */
object OffloadPolicy {

    /** [lastActive] is epoch millis of the last frame activation, null when nothing was ever recorded. */
    data class Candidate(val key: String, val lastActive: Long?, val busy: Boolean)

    /**
     * Keys to offload, oldest activity first. [activeKey] is the project whose frame is in front (null when the
     * IDE is not the active application); [maxLoaded] below 1 keeps one project all the same.
     */
    fun choose(candidates: List<Candidate>, activeKey: String?, now: Long, idleMs: Long, maxLoaded: Int, enabled: Boolean = true): List<String> {
        if (!enabled || candidates.size <= 1) return emptyList()
        val eligible = candidates
            .filter { it.key != activeKey && !it.busy }
            .sortedBy { it.lastActive ?: now }
        val chosen = mutableListOf<String>()
        fun staying() = candidates.size - chosen.size
        for (candidate in eligible) {
            if (staying() <= 1) break
            if ((candidate.lastActive ?: now) + idleMs <= now) chosen += candidate.key
        }
        for (candidate in eligible) {
            if (staying() <= maxLoaded.coerceAtLeast(1)) break
            if (candidate.key !in chosen) chosen += candidate.key
        }
        return chosen
    }
}
