package com.pronskiy.agenstorm.terminal.agents

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/** Step X3.1: a project's past sessions and their titles, from transcripts read only where they need to be. */
@OptIn(ExperimentalPathApi::class)
class ClaudeTranscriptsTest {

    private lateinit var home: Path
    private val reader = ClaudeTranscripts()

    private fun id(n: Int) = "${n}0000000-0000-4000-8000-000000000000"

    @Before
    fun copyFixtures() {
        home = Files.createTempDirectory("claude-home")
        Path.of("src/test/testData/agents/claude-home/projects").copyToRecursively(home.resolve("projects"), followLinks = false, overwrite = false)
        // Git keeps no file times: set them here, newest last.
        listOf(3 to 1_000L, 1 to 2_000L, 2 to 3_000L, 4 to 4_000L, 5 to 5_000L).forEach { (n, s) -> touch(id(n), s) }
    }

    @After
    fun delete() {
        home.deleteRecursively()
    }

    private fun file(name: String) = home.resolve("projects/-work-app/$name.jsonl")

    private fun touch(name: String, seconds: Long) {
        Files.setLastModifiedTime(file(name), FileTime.fromMillis(1_791_000_000_000 + seconds * 1000))
    }

    @Test
    fun theFolderIsTheBasePathWithEverythingButLettersAndDigitsAsDashes() {
        assertEquals(Path.of("/h/projects/-Users-me-my-app--claude-worktrees-x"), ClaudeTranscripts.folderOf(Path.of("/h"), "/Users/me/my_app/.claude/worktrees/x"))
    }

    @Test
    fun newestFirstWithTheirTitles() {
        val past = reader.recent(home, "/work/app", limit = 10, running = emptySet())

        assertEquals(listOf(id(2), id(1), id(3)), past.map { it.sessionId })
        assertEquals(listOf("renamed again by me", "fix-login", "what does this do?"), past.map { it.title })
        assertEquals("/work/app", past.first().cwd)
        assertEquals(1_791_003_000_000, past.first().lastActivity)
    }

    @Test
    fun leftOutRunningUntitledBrokenAndNotSessions() {
        val past = reader.recent(home, "/work/app", limit = 10, running = setOf(id(2)))

        assertEquals("2 runs, 4 has no title, 5 is not JSON, agent-abc is not a session id", listOf(id(1), id(3)), past.map { it.sessionId })
    }

    @Test
    fun theLimitAndNoFolder() {
        assertEquals(listOf(id(2)), reader.recent(home, "/work/app", limit = 1, running = emptySet()).map { it.sessionId })
        assertEquals(emptyList<PastSession>(), reader.recent(home, "/work/app", limit = 0, running = emptySet()))
        assertEquals(emptyList<PastSession>(), reader.recent(home, "/nowhere", limit = 10, running = emptySet()))
    }

    @Test
    fun aFileIsReadAgainOnlyWhenItChanged() {
        reader.recent(home, "/work/app", limit = 10, running = emptySet())
        val same = """{"type":"custom-title","customTitle":"changed but same size and time!!","sessionId":"x"}"""
        val path = file(id(3))
        val before = Files.getLastModifiedTime(path)
        path.writeText(same.padEnd(Files.size(path).toInt() - 1) + "\n")
        Files.setLastModifiedTime(path, before)

        assertEquals("cached", "what does this do?", reader.recent(home, "/work/app", limit = 10, running = emptySet()).last().title)

        touch(id(3), 1_500)
        assertEquals("changed but same size and time!!", reader.recent(home, "/work/app", limit = 10, running = emptySet()).last().title)
    }

    @Test
    fun aLongTranscriptIsReadAtItsEndAndElseAtItsBeginning() {
        val filler = """{"type":"assistant","message":{"content":"${"x".repeat(1000)}"}}""" + "\n"
        val big = Files.createTempFile("transcript", ".jsonl")
        try {
            big.writeText(filler.repeat(400) + """{"type":"ai-title","aiTitle":"at the end","sessionId":"s"}""" + "\n")
            assertEquals("at the end", ClaudeTranscripts.titleOf(big, Files.size(big)))

            big.writeText("""{"type":"last-prompt","lastPrompt":"at the start","sessionId":"s"}""" + "\n" + filler.repeat(400))
            assertEquals("at the start", ClaudeTranscripts.titleOf(big, Files.size(big)))

            big.writeText(filler.repeat(400))
            assertNull(ClaudeTranscripts.titleOf(big, Files.size(big)))
        } finally {
            Files.delete(big)
        }
    }

    @Test
    fun aLineCutByTheChunkIsDropped() {
        val text = """tle","aiTitle":"half"}""" + "\n" + """{"type":"ai-title","aiTitle":"whole"}""" + "\n" + """{"type":"custom-title","custom"""

        assertEquals("whole", ClaudeTranscripts.titleIn(text, cutFirst = true, cutLast = true))
    }

    @Test
    fun aTranscriptThatCouldNotBeReadIsTriedAgain() {
        val path = file(id(3))
        val permissions = Files.getPosixFilePermissions(path)
        Files.setPosixFilePermissions(path, emptySet())
        try {
            assertEquals(listOf(id(2), id(1)), reader.recent(home, "/work/app", limit = 10, running = emptySet()).map { it.sessionId })
        } finally {
            Files.setPosixFilePermissions(path, permissions)
        }

        assertEquals("same size and time, read now", listOf(id(2), id(1), id(3)), reader.recent(home, "/work/app", limit = 10, running = emptySet()).map { it.sessionId })
    }
}
