package com.pronskiy.agenstorm.terminal.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Step X2.2: a session goes to the open project nearest above its folder, or nowhere. */
class SessionGroupingTest {

    private fun session(id: String, cwd: String, startedAt: Long? = null) =
        LiveSession(pid = id.hashCode().toLong(), sessionId = id, cwd = cwd, name = null, status = null, tmuxSession = null, updatedAt = null, entrypoint = null, startedAt = startedAt)

    @Test
    fun theProjectFolderOrOneAboveIt() {
        val bases = listOf("/w/app", "/w/other")

        assertEquals("/w/app", SessionGrouping.owner("/w/app", bases))
        assertEquals("/w/app", SessionGrouping.owner("/w/app/src/deep", bases))
        assertNull("a sibling with the same prefix is not inside", SessionGrouping.owner("/w/app-hotfix", bases))
        assertNull(SessionGrouping.owner("/elsewhere", bases))
    }

    @Test
    fun theNearestOpenProjectWins() {
        val worktree = "/w/app/.claude/worktrees/bright-fox"

        assertEquals("its own project when open", worktree, SessionGrouping.owner("$worktree/src", listOf("/w/app", worktree)))
        assertEquals("the main checkout otherwise", "/w/app", SessionGrouping.owner("$worktree/src", listOf("/w/app")))
    }

    @Test
    fun trailingSlashesDoNotMatter() {
        assertEquals("/w/app/", SessionGrouping.owner("/w/app/src", listOf("/w/app/")))
        assertEquals("/w/app", SessionGrouping.owner("/w/app/", listOf("/w/app")))
    }

    @Test
    fun groupedByProjectOldestFirstOutsidersDropped() {
        val grouped = SessionGrouping.group(
            listOf(
                session("late", "/w/app", startedAt = 300),
                session("early", "/w/app/src", startedAt = 100),
                session("unknown-start", "/w/app"),
                session("there", "/w/other", startedAt = 200),
                session("nowhere", "/tmp/x", startedAt = 50),
            ),
            listOf("/w/app", "/w/other"),
        )

        assertEquals(listOf("early", "late", "unknown-start"), grouped.getValue("/w/app").map { it.sessionId })
        assertEquals(listOf("there"), grouped.getValue("/w/other").map { it.sessionId })
        assertEquals(setOf("/w/app", "/w/other"), grouped.keys)
    }
}
