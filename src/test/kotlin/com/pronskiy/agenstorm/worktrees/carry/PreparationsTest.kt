package com.pronskiy.agenstorm.worktrees.carry

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/** Step T4.8: a worktree is prepared once; whoever comes second waits for the first. */
class PreparationsTest {

    @Test
    fun theSecondCallerWaitsInsteadOfPreparingAgain() = runBlocking {
        val path = "/r/.worktrees/once-${System.nanoTime()}"
        val gate = CompletableDeferred<Unit>()
        val runs = mutableListOf<String>()
        val first = async { Preparations.once(path) { runs += "first"; gate.await() } }
        yield()
        val second = async { Preparations.once(path) { runs += "second" }; runs += "second returned" }
        yield()
        assertEquals(listOf("first"), runs)

        gate.complete(Unit)
        first.await()
        second.await()

        assertEquals(listOf("first", "second returned"), runs)
    }

    @Test
    fun aForgottenWorktreeIsPreparedAgain() = runBlocking {
        val path = "/r/.worktrees/again-${System.nanoTime()}"
        var runs = 0
        Preparations.once(path) { runs++ }
        Preparations.once(path) { runs++ }
        Preparations.forget(path)
        Preparations.once(path) { runs++ }

        assertEquals(2, runs)
    }
}
