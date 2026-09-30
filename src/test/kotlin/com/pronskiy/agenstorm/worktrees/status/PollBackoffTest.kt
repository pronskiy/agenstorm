package com.pronskiy.agenstorm.worktrees.status

import org.junit.Assert.assertEquals
import org.junit.Test

/** Step T3.2: a locked worktree is polled often while it changes and once a minute when it does not. */
class PollBackoffTest {

    private val backoff = PollBackoff(minMs = 5_000, maxMs = 60_000)

    @Test
    fun neverReadIsDue() {
        assertEquals(listOf("/a"), backoff.due(listOf("/a"), now = 0))
    }

    @Test
    fun unchangedReadsDoubleTheWaitUpToTheCapAndAChangeResetsIt() {
        var now = 0L
        val waits = mutableListOf<Long>()
        repeat(6) {
            backoff.observed("/a", changed = it == 0, now = now)
            val wait = generateSequence(now) { t -> t + 1_000 }.first { t -> backoff.due(listOf("/a"), t).isNotEmpty() } - now
            waits += wait
            now += wait
        }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), waits)

        backoff.observed("/a", changed = true, now = now)
        assertEquals(emptyList<String>(), backoff.due(listOf("/a"), now + 4_000))
        assertEquals(listOf("/a"), backoff.due(listOf("/a"), now + 5_000))
    }

    @Test
    fun aGoneWorktreeStartsOver() {
        backoff.observed("/a", changed = false, now = 0)
        backoff.retain(emptySet())

        assertEquals(listOf("/a"), backoff.due(listOf("/a"), now = 1))
    }
}
