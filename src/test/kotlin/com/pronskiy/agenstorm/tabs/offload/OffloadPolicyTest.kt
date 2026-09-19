package com.pronskiy.agenstorm.tabs.offload

import com.pronskiy.agenstorm.tabs.offload.OffloadPolicy.Candidate
import junit.framework.TestCase

/** Step P2.3: which loaded projects go — idle ones first, then the least recently active beyond the cap; never the active, a busy or the last one. */
class OffloadPolicyTest : TestCase() {

    private val hour = 3_600_000L
    private val now = 100 * hour

    private fun c(key: String, lastActiveHoursAgo: Long?, busy: Boolean = false) =
        Candidate(key, lastActiveHoursAgo?.let { now - it * hour }, busy)

    private fun choose(vararg candidates: Candidate, active: String? = null, idleHours: Long = 2, maxLoaded: Int = 8, enabled: Boolean = true) =
        OffloadPolicy.choose(candidates.toList(), active, now, idleHours * hour, maxLoaded, enabled)

    fun testIdleProjectsGoOldestFirst() {
        assertEquals(listOf("/c", "/b"), choose(c("/a", 1), c("/b", 3), c("/c", 5), active = "/a"))
    }

    fun testExactlyAtTheThresholdCountsAsIdle() {
        assertEquals(listOf("/b"), choose(c("/a", 0), c("/b", 2), active = "/a"))
        assertEquals(emptyList<String>(), choose(c("/a", 0), c("/b", 1), active = "/a"))
    }

    fun testTheActiveProjectNeverGoesHoweverIdle() {
        assertEquals(listOf("/b"), choose(c("/a", 50), c("/b", 50), active = "/a"))
    }

    fun testABusyProjectNeverGoes() {
        assertEquals(listOf("/c"), choose(c("/a", 0), c("/b", 50, busy = true), c("/c", 50), active = "/a"))
    }

    fun testTheLastLoadedProjectStays() {
        assertEquals(emptyList<String>(), choose(c("/a", 50)))
        assertEquals(listOf("/a"), choose(c("/a", 50), c("/b", 50)))
        assertEquals("a busy project is still a loaded one, so the other may go", listOf("/a"), choose(c("/a", 50), c("/b", 50, busy = true)))
    }

    fun testBeyondTheCapTheLeastRecentlyActiveGo() {
        val chosen = choose(c("/a", 0), c("/b", 1), c("/c", 1, busy = true), c("/d", 1) , c("/e", 0), active = "/a", maxLoaded = 2)
        // five loaded, two allowed: three must go, but /a is active and /c is busy, so only /b, /d and /e can.
        assertEquals(listOf("/b", "/d", "/e"), chosen)
    }

    fun testIdleOnesCountTowardsTheCap() {
        // /c is idle and goes first; that already brings four loaded down to three, so the cap of 3 takes nothing more.
        assertEquals(listOf("/c"), choose(c("/a", 0), c("/b", 1), c("/c", 5), c("/d", 1), active = "/a", maxLoaded = 3))
    }

    fun testACapOfZeroStillKeepsOneProject() {
        assertEquals(listOf("/b"), choose(c("/a", 0), c("/b", 0), active = "/a", maxLoaded = 0))
    }

    fun testNoRecordedActivityCountsAsActiveNow() {
        assertEquals(emptyList<String>(), choose(c("/a", 0), c("/b", null), active = "/a"))
        assertEquals(listOf("/c", "/b"), choose(c("/a", 0), c("/b", null), c("/c", 1), active = "/a", maxLoaded = 1))
    }

    fun testDisabledChoosesNothing() {
        assertEquals(emptyList<String>(), choose(c("/a", 0), c("/b", 50), active = "/a", enabled = false))
    }
}
