package com.pronskiy.agenstorm.worktrees.carry

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Step T4.8. A worktree is prepared once per session, by whoever gets there first — "+", its first open, or the
 * watcher that prepares a worktree as soon as it appears — and whoever comes second waits for that one to finish rather
 * than copying the same files at the same time. Removing a worktree [forget]s it, so one made again under its name is
 * prepared again.
 */
object Preparations {

    private val runs = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    suspend fun once(path: String, prepare: suspend () -> Unit) {
        val mine = CompletableDeferred<Unit>()
        val earlier = runs.putIfAbsent(path, mine)
        if (earlier != null) {
            earlier.await()
            return
        }
        try {
            prepare()
        } finally {
            mine.complete(Unit)
        }
    }

    fun forget(path: String) {
        runs.remove(path)
    }
}
