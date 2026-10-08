package com.pronskiy.agenstorm.terminal.agents

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/** Step X2.4: the model lists a running session under its project, keeps its dot until seen, and reads nothing when off. */
class AgentSessionsModelTest : BasePlatformTestCase() {

    private lateinit var home: Path
    private val model get() = AgentSessionsModel.getInstance()
    private val self = ProcessHandle.current()

    override fun setUp() {
        super.setUp()
        home = Files.createTempDirectory("claude-home")
        home.resolve("sessions").createDirectories()
        model.claudeHome = { home }
    }

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
            model.refresh()
            model.claudeHome = ClaudeLiveSessions::home
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            home.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    /** A session file for this very JVM, so it counts as running. */
    private fun writeSession(status: String, cwd: String = project.basePath!!) {
        val started = self.info().startInstant().get().toEpochMilli()
        home.resolve("sessions/${self.pid()}.json").writeText(
            """{"pid":${self.pid()},"sessionId":"test-session","cwd":"$cwd","startedAt":$started,"status":"$status","name":"x2-test"}""",
        )
    }

    private fun ownSessions(): List<SessionRow> = model.groups.value.single { it.basePath == project.basePath }.sessions

    private fun waitFor(message: String, condition: () -> Boolean) {
        model.refresh()
        PlatformTestUtil.waitWithEventsDispatching(message, condition, 10)
    }

    fun testARunningSessionIsListedUnderItsProject() {
        AgenstormSettings.getInstance().state.agentSessionsEnabled = true
        writeSession("busy", cwd = "${project.basePath}/src")

        waitFor("the session never showed") { ownSessions().isNotEmpty() }

        val row = ownSessions().single()
        assertEquals("x2-test", row.session.name)
        assertEquals("no Terminal tab runs this JVM", SessionPlace.Elsewhere, row.place)
        assertFalse(row.finished)
    }

    fun testFinishedAwayKeepsADotUntilSeen() {
        AgenstormSettings.getInstance().state.agentSessionsEnabled = true
        writeSession("busy")
        waitFor("busy never showed") { ownSessions().singleOrNull()?.session?.status == "busy" }

        writeSession("idle")
        waitFor("the dot never showed") { ownSessions().singleOrNull()?.finished == true }

        model.seen("test-session")
        assertFalse(ownSessions().single().finished)
    }

    fun testOffReadsNothing() {
        writeSession("busy")
        model.claudeHome = { fail("read ~/.claude with the setting off"); home }

        model.refresh()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        Thread.sleep(300)

        assertTrue(model.groups.value.all { it.sessions.isEmpty() })
        model.claudeHome = { home }
    }
}
