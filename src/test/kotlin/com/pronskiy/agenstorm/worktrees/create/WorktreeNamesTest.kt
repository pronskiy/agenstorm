package com.pronskiy.agenstorm.worktrees.create

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Step T2.1: the typed name becomes one folder-and-branch slug; `info/exclude` gets its line once. */
class WorktreeNamesTest {

    @Test
    fun aNameBecomesALowerCaseDashedSlug() {
        assertEquals("fix-login", WorktreeNames.slug("Fix login"))
        assertEquals("fix-login-bug", WorktreeNames.slug("  fix/login  bug!! "))
        assertEquals("tables_q.v2", WorktreeNames.slug("tables_q.v2"))
        assertEquals("agent", WorktreeNames.slug("--.agent.--"))
    }

    @Test
    fun anExistingBranchSuggestsItsLastSegment() {
        assertEquals("tables", WorktreeNames.fromBranch("feat/tables"))
        assertEquals("worktree-bright-fox", WorktreeNames.fromBranch("worktree-bright-fox"))
        assertEquals("feat", WorktreeNames.fromBranch("feat/--"))
    }

    @Test
    fun nothingUsableIsNoSlug() {
        assertNull(WorktreeNames.slug("   "))
        assertNull(WorktreeNames.slug("!!!"))
    }

    @Test
    fun aLongNameIsCutAndARefLockSuffixIsDropped() {
        assertEquals(WorktreeNames.MAX, WorktreeNames.slug("a".repeat(100))!!.length)
        assertEquals("branch", WorktreeNames.slug("branch.lock"))
    }

    @Test
    fun theExcludeLineIsAddedOnceAndOnItsOwnLine() {
        assertEquals("/.worktrees/\n", ExcludeFile.withEntry(null, "/.worktrees/"))
        assertEquals("# git ls-files --others --exclude-from=.git/info/exclude\n/.worktrees/\n", ExcludeFile.withEntry("# git ls-files --others --exclude-from=.git/info/exclude", "/.worktrees/"))
        assertEquals("*.log\n/.worktrees/\n", ExcludeFile.withEntry("*.log\n", "/.worktrees/"))
        assertNull(ExcludeFile.withEntry("*.log\n /.worktrees/ \n", "/.worktrees/"))
    }
}
