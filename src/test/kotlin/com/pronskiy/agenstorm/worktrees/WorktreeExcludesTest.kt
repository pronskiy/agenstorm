package com.pronskiy.agenstorm.worktrees

import org.junit.Assert.assertEquals
import org.junit.Test

/** Step T1.7: what a project excludes so the worktrees nested in it stay out of it. */
class WorktreeExcludesTest {

    private fun wt(path: String, main: Boolean = false) = Worktree(path, null, isMain = main, isLocked = false, lockReason = null, createdAt = 0)

    @Test
    fun bothWorktreeFoldersAreExcludedWholeEvenWithNoWorktreeYet() {
        assertEquals(listOf("/r/.worktrees", "/r/.claude/worktrees"), WorktreeExcludes.paths("/r/", ".worktrees", emptyList()))
    }

    @Test
    fun worktreesInsideThoseFoldersAddNothingOtherNestedOnesAreAddedOutsideOnesAreNot() {
        val worktrees = listOf(
            wt("/r", main = true),
            wt("/r/.worktrees/fix-login"),
            wt("/r/.claude/worktrees/bright-running-fox"),
            wt("/r/tmp/agent-7"),
            wt("/elsewhere/r-hotfix"),
            wt("/rr/next-door"),
        )

        assertEquals(listOf("/r/.worktrees", "/r/.claude/worktrees", "/r/tmp/agent-7"), WorktreeExcludes.paths("/r", ".worktrees", worktrees))
    }

    @Test
    fun aCustomFolderReplacesOursAndKeepsClaudeCodes() {
        assertEquals(listOf("/r/wt", "/r/.claude/worktrees"), WorktreeExcludes.paths("/r", "/wt/", emptyList()))
    }

    @Test
    fun theFolderSettingIsARelativePathOrTheDefault() {
        assertEquals(".worktrees", WorktreeExcludes.folder(null))
        assertEquals(".worktrees", WorktreeExcludes.folder("   "))
        assertEquals("wt", WorktreeExcludes.folder(" /wt/ "))
        assertEquals("tmp/agents", WorktreeExcludes.folder("tmp\\agents"))
        assertEquals(".worktrees", WorktreeExcludes.folder("../outside"))
        assertEquals(".worktrees", WorktreeExcludes.folder("a/./b"))
        assertEquals(".worktrees", WorktreeExcludes.folder("C:/wt"))
        assertEquals(".worktrees", WorktreeExcludes.folder("a//b"))
    }
}
