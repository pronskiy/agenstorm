package com.pronskiy.agenstorm.worktrees.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Step T3.2: who holds a worktree lock, from the reason Claude Code records, and whether that process still runs. */
class LockOwnerTest {

    private val claude = "claude session t3probe (pid 68643 start Wed Sep 30 15:48:15 2026)"
    private val started = Instant.parse("2026-09-30T15:48:15Z")

    @Test
    fun readsThePidAndTheUtcStartClaudeCodeRecords() {
        assertEquals(LockOwner(68643, started), LockOwner.parse(claude))
        assertEquals(LockOwner(7, Instant.parse("2026-10-01T09:05:00Z")), LockOwner.parse("claude session x (pid 7 start Thu Oct  1 09:05:00 2026)"))
        assertEquals(LockOwner(4242, null), LockOwner.parse("claude agent (pid 4242)"))
        assertEquals(LockOwner(4242, null), LockOwner.parse("claude agent (pid 4242 start Mon Sep 30 15:48:15 2026)"))
        assertNull(LockOwner.parse("on a USB stick"))
        assertNull(LockOwner.parse(null))
    }

    @Test
    fun liveOnlyWhileThatVeryProcessRuns() {
        assertEquals(LockOwner.State.LIVE, LockOwner.state(claude, started = { started.plusSeconds(1) }, alive = { it == 68643L }))
        assertEquals(LockOwner.State.LIVE, LockOwner.state(claude, started = { null }, alive = { true }))
        assertEquals(LockOwner.State.ENDED, LockOwner.state(claude, started = { null }, alive = { false }))
        assertEquals(LockOwner.State.ENDED, LockOwner.state(claude, started = { started.plusSeconds(3600) }, alive = { true }))
        assertEquals(LockOwner.State.LIVE, LockOwner.state("claude agent (pid 4242)", started = { Instant.EPOCH }, alive = { true }))
        assertEquals(LockOwner.State.UNKNOWN, LockOwner.state("on a USB stick", started = { null }, alive = { false }))
        assertEquals(LockOwner.State.UNKNOWN, LockOwner.state(null, started = { null }, alive = { false }))
    }
}
