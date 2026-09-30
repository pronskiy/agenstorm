package com.pronskiy.agenstorm.worktrees.carry

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

/** Step T2.2: matched and ignored, never the worktree folders or `.idea`. */
class CarryPlanTest {

    @Test
    fun withoutAnIncludeFileTheDefaultPatternIsDotEnv() {
        assertEquals(listOf("--others", "--ignored", "--directory", "-z", "--exclude=.env*"), CarryPlan.candidateArgs(null))
        assertEquals("--exclude-from=/r/.worktreeinclude", CarryPlan.candidateArgs(Path.of("/r/.worktreeinclude")).last())
    }

    @Test
    fun onlyCandidatesGitIgnoresAreKeptEitherThemselvesOrUnderAnIgnoredFolder() {
        val candidates = listOf(".env", ".env.local", "notes.env", "config/secrets/", "config/app.key")
        val ignored = listOf(".env", ".env.local", "config/", "vendor/")

        assertEquals(listOf(".env", ".env.local", "config/app.key", "config/secrets"), CarryPlan.entries(candidates, ignored, skip = emptyList()))
    }

    @Test
    fun theWorktreeFoldersIdeaAndGitAreNeverCarried() {
        val candidates = listOf(".worktrees/", ".claude/worktrees/", ".claude/settings.local.json", ".idea/", ".env")
        val ignored = candidates

        assertEquals(listOf(".claude/settings.local.json", ".env"), CarryPlan.entries(candidates, ignored, skip = listOf(".worktrees", ".claude/worktrees")))
    }

    @Test
    fun nulSeparatedOutputIsSplitHoweverItArrives() {
        assertEquals(listOf(".env", "a b/c", "x"), CarryPlan.split(listOf(".env\u0000a b/c\u0000", "x\u0000")))
    }
}
