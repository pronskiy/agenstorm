package com.pronskiy.agenstorm.worktrees.carry

import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.WorktreeSnapshot

/**
 * Step T4.8, pure. The worktrees that have just appeared, going from one registry snapshot to the next: linked ones
 * that were not there before and whose admin dir git made in the last [RECENT_MS]. The first snapshot, and one of a
 * different repository, are a baseline, not a crowd of arrivals — a worktree that was there when the IDE started is
 * prepared on its first open instead.
 */
object Arrivals {

    const val RECENT_MS = 10 * 60 * 1000L

    fun between(previous: WorktreeSnapshot, current: WorktreeSnapshot, now: Long): List<Worktree> {
        if (previous.worktrees.isEmpty() || previous.commonDir != current.commonDir) return emptyList()
        val before = previous.worktrees.map { it.path }.toSet()
        return current.worktrees.filter { !it.isMain && it.path !in before && now - it.createdAt <= RECENT_MS }
    }
}
