package com.pronskiy.agenstorm.commit.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Steps D2.3/D2.5: the `claude -p --output-format stream-json` subprocess backend against a shell script that records its invocation and replays CLI events; default model `haiku`. */
class ClaudeCliBackendTest {

    private val script = File("src/test/testData/commit/fake-claude.sh").absolutePath
    private val request = LlmRequest(system = "You write commit messages.", user = "Diff:\n+a", model = "claude-sonnet-5")

    @Test
    fun runsTheCliWithPromptOnStdinAndStreamsEachTextDelta() = runBlocking {
        val args = Files.createTempFile("fake-claude-args", ".txt").toFile()
        val stdin = Files.createTempFile("fake-claude-stdin", ".txt").toFile()
        try {
            val backend = ClaudeCliBackend(
                executable = { script },
                extraArgs = "--tools \"\" --no-session-persistence",
                environment = mapOf("FAKE_CLAUDE_ARGS_FILE" to args.path, "FAKE_CLAUDE_STDIN_FILE" to stdin.path),
            )

            val chunks = backend.stream(request).toList()

            assertEquals(listOf("feat: add thing", "\n\nBody from fake claude."), chunks)
            assertEquals(
                STREAM_ARGS + listOf("--model", "claude-sonnet-5", "--system-prompt", "You write commit messages.", "--tools", "", "--no-session-persistence"),
                recordedArgs(args),
            )
            assertEquals("Diff:\n+a", stdin.readText())
        } finally {
            args.delete()
            stdin.delete()
        }
    }

    @Test
    fun fallsBackToTheDefaultModelAndOmitsTheSystemPromptWhenAbsent() = runBlocking {
        val args = Files.createTempFile("fake-claude-args", ".txt").toFile()
        try {
            ClaudeCliBackend(executable = { script }, extraArgs = "", environment = mapOf("FAKE_CLAUDE_ARGS_FILE" to args.path))
                .stream(request.copy(system = "", model = null)).toList()
            assertEquals(STREAM_ARGS + listOf("--model", "haiku"), recordedArgs(args))
        } finally {
            args.delete()
        }
    }

    @Test
    fun anEmptyDefaultModelLeavesTheChoiceToTheCli() = runBlocking {
        val args = Files.createTempFile("fake-claude-args", ".txt").toFile()
        try {
            ClaudeCliBackend(executable = { script }, extraArgs = "", defaultModel = "", environment = mapOf("FAKE_CLAUDE_ARGS_FILE" to args.path))
                .stream(request.copy(system = "", model = null)).toList()
            assertEquals(STREAM_ARGS, recordedArgs(args))
        } finally {
            args.delete()
        }
    }

    @Test
    fun validateRunsWithTheConfiguredDefaultModel() = runBlocking {
        val args = Files.createTempFile("fake-claude-args", ".txt").toFile()
        try {
            val backend = ClaudeCliBackend(executable = { script }, extraArgs = "", defaultModel = "opus", environment = mapOf("FAKE_CLAUDE_ARGS_FILE" to args.path))
            assertNull(backend.validate())
            assertEquals(STREAM_ARGS + listOf("--model", "opus"), recordedArgs(args))
        } finally {
            args.delete()
        }
    }

    @Test
    fun disablesExtendedThinkingUnlessTheEnvironmentOverridesIt() = runBlocking {
        val env = Files.createTempFile("fake-claude-env", ".txt").toFile()
        try {
            ClaudeCliBackend(executable = { script }, environment = mapOf("FAKE_CLAUDE_ENV_FILE" to env.path)).stream(request).toList()
            assertEquals("MAX_THINKING_TOKENS=0", env.readText().trim())

            ClaudeCliBackend(executable = { script }, environment = mapOf("FAKE_CLAUDE_ENV_FILE" to env.path, "MAX_THINKING_TOKENS" to "4096")).stream(request).toList()
            assertEquals("MAX_THINKING_TOKENS=4096", env.readText().trim())
        } finally {
            env.delete()
        }
    }

    @Test
    fun defaultExtraArgsSkipToolsSessionsMcpServersAndUserCustomizations() {
        assertEquals(listOf("--tools", "", "--no-session-persistence", "--strict-mcp-config", "--safe-mode"), com.intellij.util.execution.ParametersListUtil.parse(ClaudeCliBackend.DEFAULT_EXTRA_ARGS))
    }

    private fun recordedArgs(file: File): List<String> = file.readText().removeSuffix("\n").split("\n")

    private companion object {
        val STREAM_ARGS = listOf("-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages")
    }

    @Test
    fun chunksArriveWhileTheProcessIsStillRunning() = runBlocking {
        val arrivals = mutableListOf<Long>()
        ClaudeCliBackend(executable = { script }, environment = mapOf("FAKE_CLAUDE_DELTA_SLEEP" to "1"))
            .stream(request).collect { arrivals += System.currentTimeMillis() }
        assertEquals(2, arrivals.size)
        val gap = arrivals[1] - arrivals[0]
        assertTrue("second chunk came only $gap ms after the first; the first should not wait for the process to end", gap >= 500)
    }

    @Test
    fun emitsTheResultTextWhenTheCliSendsNoPartialMessages() = runBlocking {
        val chunks = ClaudeCliBackend(executable = { script }, environment = mapOf("FAKE_CLAUDE_MODE" to "no-deltas")).stream(request).toList()
        assertEquals(listOf("feat: add thing\n\nBody from fake claude."), chunks)
    }

    @Test
    fun anErrorResultBecomesAnLlmExceptionWithItsMessage() {
        val error = assertThrows(LlmException::class.java) {
            runBlocking { ClaudeCliBackend(executable = { script }, environment = mapOf("FAKE_CLAUDE_MODE" to "error-result")).stream(request).toList() }
        }
        assertEquals("There is an issue with the selected model (bogus).", error.message)
    }

    @Test
    fun nonZeroExitBecomesAnLlmExceptionWithStderr() {
        val error = assertThrows(LlmException::class.java) {
            runBlocking { ClaudeCliBackend(executable = { script }, environment = mapOf("FAKE_CLAUDE_FAIL" to "1")).stream(request).toList() }
        }
        assertEquals("boom: not logged in", error.message)
    }

    @Test
    fun missingExecutableFailsBeforeRunning() {
        val error = assertThrows(LlmException::class.java) {
            runBlocking { ClaudeCliBackend(executable = { null }).stream(request).toList() }
        }
        assertTrue(error.message!!, error.message!!.contains("claude"))
        assertEquals(error.message, runBlocking { ClaudeCliBackend(executable = { null }).validate() })
    }

    @Test
    fun timeoutBecomesAnLlmException() {
        val error = assertThrows(LlmException::class.java) {
            runBlocking { ClaudeCliBackend(executable = { script }, environment = mapOf("FAKE_CLAUDE_SLEEP" to "5"), timeoutMs = 500).stream(request).toList() }
        }
        assertTrue(error.message!!, error.message!!.contains("timed out"))
    }

    @Test
    fun cancellationKillsTheProcessPromptly() {
        val started = System.currentTimeMillis()
        val result = runBlocking {
            withTimeoutOrNull(1_000) {
                ClaudeCliBackend(executable = { script }, environment = mapOf("FAKE_CLAUDE_SLEEP" to "10")).stream(request).toList()
            }
        }
        val elapsed = System.currentTimeMillis() - started
        assertNull(result)
        assertTrue("took $elapsed ms", elapsed < 5_000)
    }

    @Test
    fun validateIsNullForAWorkingExecutable() = runBlocking {
        assertNull(ClaudeCliBackend(executable = { script }).validate())
    }

    @Test
    fun discoveryPrefersTheConfiguredPathAndReportsAMissingOne() {
        assertEquals(script, ClaudeCliBackend.discover(script))
        assertNull(ClaudeCliBackend.discover("/definitely/not/here/claude"))
    }
}
