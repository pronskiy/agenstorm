package com.pronskiy.agenstorm.commit.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Step D1.1: the backend contract, exercised through the in-memory FakeBackend that tests and the D1 guardrail use. */
class FakeBackendTest {

    private val request = LlmRequest(system = "You write commit messages.", user = "Diff:\n+a", model = null)

    @Test
    fun emitsTheConfiguredChunksInOrder() = runBlocking {
        val backend = FakeBackend(listOf("feat: a", "dd thing\n", "\nBody."), delayMs = 0)

        assertEquals("fake", backend.id)
        assertEquals(listOf("feat: a", "dd thing\n", "\nBody."), backend.stream(request).toList())
        assertNull(backend.validate())
    }

    @Test
    fun failsWithAnLlmExceptionAfterTheChunksWhenConfiguredTo() = runBlocking {
        val backend = FakeBackend(listOf("partial"), delayMs = 0, failure = "boom")

        val collected = ArrayList<String>()
        val error = assertThrows(LlmException::class.java) {
            runBlocking { backend.stream(request).collect { collected += it } }
        }
        assertEquals(listOf("partial"), collected)
        assertEquals("boom", error.message)
        assertEquals("boom", backend.validate())
    }

    @Test
    fun requestDefaultsToAThousandTokensAndNoModel() {
        val request = LlmRequest(system = "s", user = "u")
        assertEquals(1024, request.maxTokens)
        assertNull(request.model)
    }

    @Test
    fun defaultFakeMessageIsAValidSubjectAndBody() = runBlocking {
        val text = FakeBackend(delayMs = 0).stream(request).toList().joinToString("")
        val lines = text.lines()
        assertEquals(true, lines.first().length <= 72)
        assertEquals("", lines[1])
        assertEquals(true, lines.size >= 3)
    }
}
