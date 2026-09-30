package com.pronskiy.agenstorm.worktrees

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step T1.6: the switch opens first, closes last, and never forces anything busy closed. */
class SwitchRunnerTest {

    private val main = "/r"
    private val a = "/r/.worktrees/a"
    private val b = "/r/.worktrees/b"
    private val worktrees = setOf(main, a, b)

    private class FakeEnv(
        val open: MutableSet<String>,
        val busy: MutableSet<String> = mutableSetOf(),
        val missing: Set<String> = emptySet(),
        val failing: Set<String> = emptySet(),
    ) : SwitchRunner.Env {
        val calls = mutableListOf<String>()
        val notices = mutableListOf<String>()

        override fun loaded(paths: Set<String>) = open.intersect(paths)
        override fun isBusy(path: String) = path in busy
        override fun exists(path: String) = path !in missing
        override suspend fun open(path: String): Boolean {
            calls += "open $path"
            if (path in failing) return false
            open += path
            return true
        }
        override fun present(target: String, like: String) {
            calls += "present $target like $like"
        }
        override fun close(path: String): Boolean {
            if (path in busy) return false
            calls += "close $path"
            open -= path
            return true
        }
        override fun notify(message: String) {
            notices += message
        }
    }

    @Test
    fun anIdleSwapOpensThenPresentsThenCloses() = runBlocking {
        val env = FakeEnv(open = mutableSetOf(main))

        assertTrue(SwitchRunner(env).switch(main, a, worktrees))

        assertEquals(listOf("open $a", "present $a like $main", "close $main"), env.calls)
        assertEquals(setOf(a), env.open)
    }

    @Test
    fun aBusyWorktreeIsLeftBehindAndClosedOnALaterSwitchOnceIdle() = runBlocking {
        val env = FakeEnv(open = mutableSetOf(main), busy = mutableSetOf(main))
        val runner = SwitchRunner(env)

        runner.switch(main, a, worktrees)
        assertEquals(setOf(main, a), env.open)
        assertEquals(setOf(main), runner.leftBehind)

        env.busy.clear()
        env.calls.clear()
        runner.switch(a, b, worktrees)

        assertEquals(listOf("open $b", "present $b like $a", "close $a", "close $main"), env.calls)
        assertEquals(setOf(b), env.open)
        assertEquals(emptySet<String>(), runner.leftBehind)
    }

    @Test
    fun aGuardObjectingAtTheLastMomentLeavesTheWorktreeOpenAndRemembered() = runBlocking {
        val env = object : SwitchRunner.Env by FakeEnv(open = mutableSetOf(main)) {
            override fun isBusy(path: String) = false
            override fun close(path: String) = false
        }
        val runner = SwitchRunner(env)

        assertTrue(runner.switch(main, a, worktrees))
        assertEquals(setOf(main), runner.leftBehind)
    }

    @Test
    fun anOpenThatFailsClosesNothingAndSaysSo() = runBlocking {
        val env = FakeEnv(open = mutableSetOf(main), failing = setOf(a))

        assertFalse(SwitchRunner(env).switch(main, a, worktrees))

        assertEquals(listOf("open $a"), env.calls)
        assertEquals(setOf(main), env.open)
        assertEquals(1, env.notices.size)
    }

    @Test
    fun aWorktreeWhoseFolderIsGoneIsNotOpened() = runBlocking {
        val env = FakeEnv(open = mutableSetOf(main), missing = setOf(a))

        assertFalse(SwitchRunner(env).switch(main, a, worktrees))

        assertEquals(emptyList<String>(), env.calls)
        assertEquals(1, env.notices.size)
    }

    @Test
    fun switchingToAnOpenWorktreeFocusesItWithoutOpening() = runBlocking {
        val env = FakeEnv(open = mutableSetOf(main, a))

        assertTrue(SwitchRunner(env).switch(main, a, worktrees))

        assertEquals(listOf("present $a like $main", "close $main"), env.calls)
    }
}
