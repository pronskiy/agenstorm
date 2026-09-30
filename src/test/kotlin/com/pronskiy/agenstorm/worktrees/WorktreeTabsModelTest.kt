package com.pronskiy.agenstorm.worktrees

import org.junit.Assert.assertEquals
import org.junit.Test

/** Step T1.3: order, labels and the current tab of the worktree strip. */
class WorktreeTabsModelTest {

    private fun wt(path: String, branch: String? = null, main: Boolean = false, created: Long = 0) =
        Worktree(path, branch, isMain = main, isLocked = false, lockReason = null, createdAt = created)

    private fun labels(tabs: List<WorktreeTab>) = tabs.map { it.label }

    @Test
    fun mainComesFirstLabelledWithItsBranchThenTheRestOldestFirst() {
        val snapshot = WorktreeSnapshot("/r/.git", listOf(
            wt("/r/.worktrees/tables-q", "feat/tables", created = 300),
            wt("/r", "main", main = true),
            wt("/r/.worktrees/fix-login", "fix/login", created = 100),
        ))

        val tabs = WorktreeTabsModel.tabs(snapshot, currentPath = "/r/.worktrees/fix-login")

        assertEquals(listOf("main", "fix-login", "tables-q"), labels(tabs))
        assertEquals(listOf(false, true, false), tabs.map { it.isCurrent })
        assertEquals("/r/.worktrees/fix-login", tabs[1].path)
    }

    @Test
    fun aDetachedMainIsLabelledWithItsFolder() {
        val tabs = WorktreeTabsModel.tabs(WorktreeSnapshot(null, listOf(wt("/p/agenstorm", branch = null, main = true))), "/p/agenstorm")

        assertEquals(listOf("agenstorm"), labels(tabs))
        assertEquals(listOf(true), tabs.map { it.isCurrent })
    }

    @Test
    fun sameFolderNamesGetTheirParentAndTiesKeepAStableOrder() {
        val snapshot = WorktreeSnapshot("/r/.git", listOf(
            wt("/r/.worktrees/foo", "a", created = 50),
            wt("/r/.claude/worktrees/foo", "b", created = 50),
            wt("/r", "foo", main = true),
        ))

        val tabs = WorktreeTabsModel.tabs(snapshot, currentPath = "/elsewhere")

        assertEquals(listOf("foo", "foo · .claude/worktrees", "foo · .worktrees"), labels(tabs))
        assertEquals(listOf("/r", "/r/.claude/worktrees/foo", "/r/.worktrees/foo"), tabs.map { it.path })
        assertEquals(listOf(false, false, false), tabs.map { it.isCurrent })
    }

    @Test
    fun clashingWorktreesOutsideTheMainCheckoutGetTheirParentFolder() {
        val snapshot = WorktreeSnapshot("/r/.git", listOf(
            wt("/r", "main", main = true),
            wt("/w/one/api", "a", created = 1),
            wt("/w/two/api", "b", created = 2),
        ))

        assertEquals(listOf("main", "api · one", "api · two"), labels(WorktreeTabsModel.tabs(snapshot, "/r")))
    }

    @Test
    fun anEmptySnapshotHasNoTabs() {
        assertEquals(emptyList<WorktreeTab>(), WorktreeTabsModel.tabs(WorktreeSnapshot.EMPTY, "/r"))
    }
}
