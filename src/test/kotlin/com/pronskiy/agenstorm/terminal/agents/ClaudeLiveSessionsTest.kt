package com.pronskiy.agenstorm.terminal.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Step X2.1: Claude Code's session files, as 2.1.29x writes them, read without trusting them. */
class ClaudeLiveSessionsTest {

    private val home: Path = Path.of("src/test/testData/agents/claude-home")

    private val everyoneAlive: (Long, Long?) -> Boolean = { _, _ -> true }

    @Test
    fun aFullFile() {
        val session = ClaudeLiveSessions.read(home, everyoneAlive).single { it.pid == 101L }

        assertEquals(
            LiveSession(
                pid = 101,
                sessionId = "aaaaaaaa-1111-4111-8111-111111111111",
                cwd = "/work/app",
                name = "fix-login",
                status = "idle",
                tmuxSession = "app-1a2b",
                updatedAt = 1791495015923,
                entrypoint = "cli",
                startedAt = 1791494204512,
            ),
            session,
        )
    }

    @Test
    fun missingFieldsAreNullAndUnknownOnesIgnored() {
        val sessions = ClaudeLiveSessions.read(home, everyoneAlive).associateBy { it.pid }

        assertEquals("busy", sessions.getValue(102).status)
        assertEquals("claude-desktop", sessions.getValue(102).entrypoint)
        assertNull("no tmux field", sessions.getValue(102).tmuxSession)
        assertEquals("waiting", sessions.getValue(103).status)
        assertNull("an empty tmux field is none", sessions.getValue(103).tmuxSession)
        assertNull(sessions.getValue(103).name)
        assertNull(sessions.getValue(103).updatedAt)
    }

    @Test
    fun filesThatAreNotSessionsAreSkipped() {
        val pids = ClaudeLiveSessions.read(home, everyoneAlive).map { it.pid }

        assertEquals("no sessionId (104), not JSON (105), a key file (106), not an object (108)", listOf(101L, 102L, 103L, 107L), pids)
    }

    @Test
    fun onlySessionsWhoseProcessRunsAreKept() {
        val seen = mutableListOf<Pair<Long, Long?>>()
        val sessions = ClaudeLiveSessions.read(home) { pid, startedAt -> seen += pid to startedAt; pid != 107L }

        assertEquals(listOf(101L, 102L, 103L), sessions.map { it.pid })
        assertTrue("the start time from the file is handed over", 101L to 1791494204512L in seen)
        assertTrue(103L to null in seen)
    }

    @Test
    fun noSessionsFolderIsNoSessions() {
        val empty = Files.createTempDirectory("claude-home")
        try {
            assertEquals(emptyList<LiveSession>(), ClaudeLiveSessions.read(empty, everyoneAlive))
        } finally {
            Files.delete(empty)
        }
    }

    @Test
    fun aProcessThatIsNotRunningIsNotAlive() {
        assertFalse(ClaudeLiveSessions.isAlive(Long.MAX_VALUE, null))
    }

    @Test
    fun thisProcessIsAliveOnlyWithItsOwnStartTime() {
        val self = ProcessHandle.current()
        val started = self.info().startInstant().get().toEpochMilli()

        assertTrue(ClaudeLiveSessions.isAlive(self.pid(), started + 2_000))
        assertTrue("no start time in the file", ClaudeLiveSessions.isAlive(self.pid(), null))
        assertFalse("a pid given to a later process", ClaudeLiveSessions.isAlive(self.pid(), started - 3_600_000))
    }

    @Test
    fun parseReadsOneFile() {
        assertEquals("cccccccc-3333-4333-8333-333333333333", ClaudeLiveSessions.parse("""{"pid":1,"sessionId":"cccccccc-3333-4333-8333-333333333333","cwd":"/w"}""")?.sessionId)
        assertNull(ClaudeLiveSessions.parse("""{"pid":"one","sessionId":"x","cwd":"/w"}"""))
        assertNull(ClaudeLiveSessions.parse(""))
    }
}
