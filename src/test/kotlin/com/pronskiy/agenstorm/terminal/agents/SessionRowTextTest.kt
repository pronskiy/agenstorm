package com.pronskiy.agenstorm.terminal.agents

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Step X2.5: what a session row says. */
class SessionRowTextTest : BasePlatformTestCase() {

    private fun session(name: String? = "fix-login", tmux: String? = null, status: String? = "idle") =
        LiveSession(pid = 1, sessionId = "aaaaaaaa-1111-4111-8111-111111111111", cwd = "/w/app", name = name, status = status, tmuxSession = tmux, updatedAt = 0, entrypoint = "cli")

    fun testTheTitleIsClaudesNameElseTheStartOfTheId() {
        assertEquals("fix-login", SessionRowText.title(session()))
        assertEquals("aaaaaaaa", SessionRowText.title(session(name = null)))
    }

    fun testAgesInOneShortWord() {
        val now = 10_000_000_000L
        assertEquals("now", SessionRowText.ago(now - 59_000, now))
        assertEquals("1m", SessionRowText.ago(now - 60_000, now))
        assertEquals("59m", SessionRowText.ago(now - 3_599_000, now))
        assertEquals("1h", SessionRowText.ago(now - 3_600_000, now))
        assertEquals("1d", SessionRowText.ago(now - 86_400_000, now))
        assertEquals("a clock that moved back is now", "now", SessionRowText.ago(now + 5_000, now))
        assertNull(SessionRowText.ago(null, now))
    }

    fun testHintsSayWhereItRunsWhenThatIsNews() {
        assertNull("a plain tab", SessionRowText.hint(SessionRow(session(), SessionPlace.InTab(fakeTab(project)), finished = false)))
        assertEquals("tmux", SessionRowText.hint(SessionRow(session(tmux = "app-1a2b"), SessionPlace.InTab(fakeTab(project)), finished = false)))
        assertEquals("background", SessionRowText.hint(SessionRow(session(tmux = "app-1a2b"), SessionPlace.Background("app-1a2b"), finished = false)))
        assertEquals("outside the IDE", SessionRowText.hint(SessionRow(session(), SessionPlace.Elsewhere, finished = false)))
    }

    fun testTheTooltipQuotesClaudeAndSaysWhatAClickDoes() {
        assertEquals("Claude: waiting. Click to bring up its Terminal tab.", SessionRowText.tooltip(SessionRow(session(status = "waiting"), SessionPlace.InTab(fakeTab(project)), finished = false)))
        assertEquals(
            "Claude: no status. It runs outside the IDE, so there is nothing to bring up here.",
            SessionRowText.tooltip(SessionRow(session(status = null), SessionPlace.Elsewhere, finished = false)),
        )
    }
}
