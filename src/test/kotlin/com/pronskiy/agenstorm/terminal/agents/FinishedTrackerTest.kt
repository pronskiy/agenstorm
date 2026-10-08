package com.pronskiy.agenstorm.terminal.agents

import org.junit.Assert.assertEquals
import org.junit.Test

/** Step X2.4: a dot for a session that stopped working while its project was not in front. */
class FinishedTrackerTest {

    private val tracker = FinishedTracker()

    private fun session(id: String, status: String?) =
        LiveSession(pid = 1, sessionId = id, cwd = "/w/app", name = null, status = status, tmuxSession = null, updatedAt = null, entrypoint = null)

    @Test
    fun busyThenIdleAwayIsFinished() {
        tracker.update(listOf(session("a", "busy") to "/w/app"), front = "/w/other")

        assertEquals(setOf("a"), tracker.update(listOf(session("a", "idle") to "/w/app"), front = "/w/other"))
    }

    @Test
    fun waitingForTheUserCountsToo() {
        tracker.update(listOf(session("a", "busy") to "/w/app"), front = null)

        assertEquals(setOf("a"), tracker.update(listOf(session("a", "waiting") to "/w/app"), front = null))
    }

    @Test
    fun finishingInFrontIsNoNews() {
        tracker.update(listOf(session("a", "busy") to "/w/app"), front = "/w/app")

        assertEquals(emptySet<String>(), tracker.update(listOf(session("a", "idle") to "/w/app"), front = "/w/app"))
    }

    @Test
    fun theDotStaysUntilSeenBusyAgainOrGone() {
        tracker.update(listOf(session("a", "busy") to "/w/app"), front = null)
        tracker.update(listOf(session("a", "idle") to "/w/app"), front = null)

        assertEquals("a later idle read keeps it", setOf("a"), tracker.update(listOf(session("a", "idle") to "/w/app"), front = "/w/app"))
        tracker.seen("a")
        assertEquals(emptySet<String>(), tracker.update(listOf(session("a", "idle") to "/w/app"), front = null))

        tracker.update(listOf(session("a", "busy") to "/w/app"), front = null)
        tracker.update(listOf(session("a", "idle") to "/w/app"), front = null)
        assertEquals("busy again", emptySet<String>(), tracker.update(listOf(session("a", "busy") to "/w/app"), front = null))

        tracker.update(listOf(session("a", "idle") to "/w/app"), front = null)
        assertEquals("gone", emptySet<String>(), tracker.update(emptyList(), front = null))
    }

    @Test
    fun idleFromTheStartIsNotFinished() {
        assertEquals(emptySet<String>(), tracker.update(listOf(session("a", "idle") to "/w/app"), front = null))
    }

    @Test
    fun currentSeesAClickSinceTheLastRead() {
        tracker.update(listOf(session("a", "busy") to "/w/app"), front = null)
        tracker.update(listOf(session("a", "idle") to "/w/app"), front = null)

        tracker.seen("a")

        assertEquals(emptySet<String>(), tracker.current())
    }
}
