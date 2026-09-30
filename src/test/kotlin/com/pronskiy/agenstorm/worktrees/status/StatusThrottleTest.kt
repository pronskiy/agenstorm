package com.pronskiy.agenstorm.worktrees.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Step T3.2: one run per worktree every interval, and a request inside the window waits for its end. */
class StatusThrottleTest {

    private val throttle = StatusThrottle(intervalMs = 3000)

    @Test
    fun neverRunIsDueAtOnceInAStableOrder() {
        assertEquals(listOf("/a", "/b"), throttle.due(setOf("/b", "/a"), now = 0))
        assertEquals(0L, throttle.wait(setOf("/a"), now = 0))
        assertNull(throttle.wait(emptySet(), now = 0))
    }

    @Test
    fun aRunHoldsItsWorktreeForTheIntervalAndTheRequestWaitsInsteadOfBeingDropped() {
        throttle.ran("/a", now = 1000)

        assertEquals(listOf("/b"), throttle.due(setOf("/a", "/b"), now = 2000))
        assertEquals(2000L, throttle.wait(setOf("/a"), now = 2000))
        assertEquals(listOf("/a"), throttle.due(setOf("/a"), now = 4000))
        assertEquals(0L, throttle.wait(setOf("/a"), now = 5000))
    }

    @Test
    fun aGoneWorktreeIsForgotten() {
        throttle.ran("/a", now = 1000)
        throttle.retain(setOf("/b"))

        assertEquals(listOf("/a"), throttle.due(setOf("/a"), now = 1500))
    }
}
