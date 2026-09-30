package com.pronskiy.agenstorm.worktrees.status

/**
 * Step T3.2, pure. How often a locked worktree nobody has open is polled: every [minMs] while its status keeps
 * changing, doubling up to [maxMs] each time a read finds it unchanged — so a lock left behind (by hand, or by a
 * crashed agent) costs one `git status` a minute, not one every tick. Any read counts, whatever asked for it.
 * Synchronized: the service's worker records the reads and its tick asks what is due.
 */
class PollBackoff(private val minMs: Long, private val maxMs: Long) {

    private val interval = HashMap<String, Long>()
    private val next = HashMap<String, Long>()

    @Synchronized
    fun due(paths: Collection<String>, now: Long): List<String> = paths.filter { now >= (next[it] ?: 0L) }

    @Synchronized
    fun observed(path: String, changed: Boolean, now: Long) {
        val step = if (changed) minMs else minOf(maxMs, (interval[path] ?: minMs) * 2)
        interval[path] = step
        next[path] = now + step
    }

    @Synchronized
    fun retain(paths: Set<String>) {
        interval.keys.retainAll(paths)
        next.keys.retainAll(paths)
    }
}
