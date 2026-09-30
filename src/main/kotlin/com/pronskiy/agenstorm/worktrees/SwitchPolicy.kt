package com.pronskiy.agenstorm.worktrees

/**
 * Step T1.6, pure: what a click on a worktree tab does (decisions 73 and 74). The window moves to [Plan.target] —
 * opened when no project has it yet, focused when one has — and the worktree it leaves is closed, unless something is
 * running in it: then it stays open behind the target and is remembered as *left behind*. On every switch, the
 * worktrees left behind earlier that are idle by now are closed too. A worktree opened in a window of its own on
 * purpose is never in that set, so no switch closes it.
 *
 * With [keepCurrent] (the setting `worktreesKeepCurrentOpen`, Roman 2026-09-30: "possible to replace current project or
 * open new") nothing is closed at all: each worktree switched to stays open as a project of its own, with a project tab
 * of its own, and switching back to one focuses it.
 */
object SwitchPolicy {

    data class Plan(
        val target: String,
        val open: Boolean,
        val closeCurrent: Boolean,
        val closeLeftBehind: List<String>,
        val leftBehind: Set<String>,
    )

    /**
     * [loaded] are the worktrees of this repository that have an open project, [busy] those of them a guard objects
     * to closing, [leftBehind] what earlier switches left open. Null when [target] is the worktree already shown.
     */
    fun decide(target: String, current: String, loaded: Set<String>, busy: Set<String>, leftBehind: Set<String>, keepCurrent: Boolean = false): Plan? {
        if (target == current) return null
        if (keepCurrent) return Plan(target, open = target !in loaded, closeCurrent = false, closeLeftBehind = emptyList(), leftBehind = leftBehind - target)
        val currentBusy = current in busy
        val stillOpen = leftBehind.filter { it in loaded && it != target && it != current }
        val closeLeftBehind = stillOpen.filterNot { it in busy }
        val next = stillOpen.filter { it in busy }.toMutableSet()
        if (currentBusy) next += current
        return Plan(
            target = target,
            open = target !in loaded,
            closeCurrent = !currentBusy,
            closeLeftBehind = closeLeftBehind,
            leftBehind = next,
        )
    }
}
