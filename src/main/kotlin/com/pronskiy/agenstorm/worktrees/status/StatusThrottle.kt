package com.pronskiy.agenstorm.worktrees.status

/**
 * Step T3.2, pure. At most one status run per worktree every [intervalMs]: a request inside the window waits for
 * its end rather than being dropped, so the last change is always read. Not thread-safe — the service's one worker
 * coroutine owns it.
 */
class StatusThrottle(private val intervalMs: Long) {

    private val lastRun = HashMap<String, Long>()

    /** The [pending] worktrees whose window is over, in a stable order. */
    fun due(pending: Collection<String>, now: Long): List<String> =
        pending.filter { path -> lastRun[path]?.let { now - it >= intervalMs } ?: true }.sorted()

    fun ran(path: String, now: Long) {
        lastRun[path] = now
    }

    /** How long until the next of [pending] is due; null when nothing is pending. */
    fun wait(pending: Collection<String>, now: Long): Long? =
        pending.minOfOrNull { path -> lastRun[path]?.let { it + intervalMs - now } ?: 0L }?.coerceAtLeast(0L)

    /** Drops the worktrees that are gone. */
    fun retain(paths: Set<String>) {
        lastRun.keys.retainAll(paths)
    }
}
