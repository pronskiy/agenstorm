package com.pronskiy.agenstorm.worktrees.cleanup

import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.Plan

/**
 * Step T5.3 (decision 94), pure. Which worktrees a cleanup may take: those whose removal loses nothing — a
 * [RemovalPlan] that is ready with no risk at all (clean, nothing its base lacks, no live lock, not busy, nothing running
 * in its terminals) — and that no window shows. Air's cleanup after N idle days can delete uncommitted changes; this one
 * never does. Last activity is the newest of the worktree's creation, the last write to its HEAD's reflog (a commit, a
 * checkout — `logs/HEAD` in its admin dir) and the last save of its project's `workspace.xml`, the last time the IDE
 * had it open. All three in epoch milliseconds.
 */
object StaleWorktrees {

    const val DEFAULT_DAYS: Int = 30
    val DAYS: IntRange = 1..365
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** One linked worktree, what removing it would mean, and when it was last used. */
    data class Candidate(val worktree: Worktree, val plan: Plan, val lastActive: Long, val open: Boolean) {
        val name: String get() = worktree.path.trimEnd('/').substringAfterLast('/')
    }

    data class Split(val removable: List<Candidate>, val kept: List<Candidate>)

    fun removable(candidate: Candidate): Boolean =
        !candidate.worktree.isMain && !candidate.open && candidate.plan is Plan.Ready && candidate.plan.risks.isEmpty()

    /** The removable ones, least recently used first, and the rest by name; the main checkout is neither. */
    fun split(candidates: List<Candidate>): Split {
        val linked = candidates.filter { !it.worktree.isMain }
        val (removable, kept) = linked.partition(::removable)
        return Split(removable.sortedBy { it.lastActive }, kept.sortedBy { it.name })
    }

    /** What the automatic cleanup takes: removable, and unused for at least [days] days by [now]. */
    fun stale(candidates: List<Candidate>, now: Long, days: Int): List<Candidate> =
        split(candidates).removable.filter { now - it.lastActive >= days * DAY_MS }

    fun lastActive(createdAt: Long, reflogWritten: Long?, workspaceSaved: Long?): Long =
        maxOf(createdAt, reflogWritten ?: 0, workspaceSaved ?: 0)
}
