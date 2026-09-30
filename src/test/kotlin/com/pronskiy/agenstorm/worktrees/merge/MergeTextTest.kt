package com.pronskiy.agenstorm.worktrees.merge

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.worktrees.merge.MergePlan.Plan
import com.pronskiy.agenstorm.worktrees.merge.MergeRunner.Outcome

/** Step T4.3: a refusal names what is in the way; the outcome says where the work went. */
class MergeTextTest : BasePlatformTestCase() {

    private val plan = Plan.Ready("fix-login", "main", "/r", 2, emptyList())

    fun testRefusals() {
        assertEquals("fix-login was not merged back: main has uncommitted changes at /r: composer.lock.", MergeText.refused("fix-login", Plan.BaseDirty("main", "/r", listOf("composer.lock"))))
        assertEquals("fix-login was not merged back: release is not checked out in any worktree.", MergeText.refused("fix-login", Plan.BaseNotCheckedOut("release")))
        assertEquals("fix-login has nothing that main lacks.", MergeText.refused("fix-login", Plan.NothingToMerge("main")))
        assertEquals("loose cannot be merged back: its HEAD is detached.", MergeText.refused("loose", Plan.NoBranch))
        assertNull(MergeText.refused("x", plan))
    }

    fun testOutcomes() {
        assertEquals("Squashed fix-login into main: the changes are staged at /r, ready to commit.", MergeText.outcome("fix-login", plan, "/r", Outcome.Merged(MergePlan.Strategy.SQUASH)))
        assertEquals("Merged fix-login into main: rebased and fast-forwarded.", MergeText.outcome("fix-login", plan, "/r", Outcome.Merged(MergePlan.Strategy.REBASE)))
        assertEquals(
            "Merging fix-login into main stopped on conflicts in app.php, login.php. main is as it was; resolve them in fix-login, then merge back again.",
            MergeText.outcome("fix-login", plan, "/r", Outcome.Conflict(listOf("app.php", "login.php"))),
        )
    }
}
