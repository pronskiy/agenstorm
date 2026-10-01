package com.pronskiy.agenstorm.terminal.tmux

import com.pronskiy.agenstorm.terminal.tmux.TmuxBackgroundPlan.Decided
import com.pronskiy.agenstorm.terminal.tmux.TmuxCloseDialog.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step U3.2: a quit's answer is acted on window by window, each session once, and only for a minute. */
class TmuxCloseGuardTest {

    @Test
    fun eachClosingWindowTakesTheSessionsItsTabsShowOnce() {
        val quit = TmuxCloseGuard.Decision(Answer.KEEP, listOf(Decided("app-a", "Local"), Decided("fix-b", null), Decided("both-c", "Local (2)")))

        assertEquals(listOf("app-a", "both-c"), quit.take(setOf("app-a", "both-c", "idle-d")).map { it.session })
        assertEquals("the second window showing both-c", listOf("fix-b"), quit.take(setOf("fix-b", "both-c")).map { it.session })
        assertTrue(quit.take(setOf("app-a", "fix-b", "both-c")).isEmpty())
    }

    @Test
    fun anAnswerCountsForAMinute() {
        val answer = TmuxCloseGuard.Decision(Answer.TERMINATE, emptyList(), at = 1_000)

        assertTrue(answer.isRecent(now = 61_000))
        assertFalse(answer.isRecent(now = 61_001))
    }
}
