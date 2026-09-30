package com.pronskiy.agenstorm.worktrees

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step T3.2: the registry re-lists on what can change the list, not on every index write T3.2 now also sees. */
class WorktreeRegistryTest {

    private val dir = "/r/.git/worktrees"

    @Test
    fun anAdminDirComingOrGoingOrItsLockGitdirOrHeadRelists() {
        assertTrue(WorktreeRegistry.changesList(dir, "$dir/new-one"))
        assertTrue(WorktreeRegistry.changesList(dir, "$dir/a/locked"))
        assertTrue(WorktreeRegistry.changesList(dir, "$dir/a/gitdir"))
        assertTrue(WorktreeRegistry.changesList(dir, "$dir/a/HEAD"))
    }

    @Test
    fun anIndexWriteAReflogLineOrAnythingOutsideDoesNot() {
        assertFalse(WorktreeRegistry.changesList(dir, "$dir/a/index"))
        assertFalse(WorktreeRegistry.changesList(dir, "$dir/a/index.lock"))
        assertFalse(WorktreeRegistry.changesList(dir, "$dir/a/logs/HEAD"))
        assertFalse(WorktreeRegistry.changesList(dir, "/r/.git/refs/heads/main"))
        assertFalse(WorktreeRegistry.changesList(dir, dir))
    }
}
