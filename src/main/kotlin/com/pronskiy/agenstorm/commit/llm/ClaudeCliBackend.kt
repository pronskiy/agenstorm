package com.pronskiy.agenstorm.commit.llm

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.util.execution.ParametersListUtil
import com.pronskiy.agenstorm.core.AgenstormBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The local `claude` CLI: `claude -p --output-format text --model <model> [--system-prompt …] <extra args>` with
 * the user prompt on stdin. The model is the request's, else [defaultModel] ([DEFAULT_MODEL] unless configured).
 * Text mode does not stream, so stdout is emitted as one chunk on exit 0; a non-zero exit becomes an
 * [LlmException] carrying stderr. Cancelling the collector destroys the process. Flags that may drift between
 * CLI versions live in the "extra arguments" setting ([DEFAULT_EXTRA_ARGS]).
 *
 * Speed: Claude Code enables extended thinking by default, which made a one-line commit message take 20–50 s
 * even on Haiku; [DEFAULT_ENVIRONMENT] switches it off (~3 s end to end). `--safe-mode` in the default extra
 * args starts the CLI without the user's customizations (CLAUDE.md, plugins, skills, hooks, MCP servers), which
 * cut the prompt from ~6,000 to ~800 tokens and keeps the output independent of the machine's Claude Code setup;
 * `--strict-mcp-config` additionally guards against MCP servers coming back through other flags.
 */
class ClaudeCliBackend(
    private val executable: () -> String?,
    private val extraArgs: String = DEFAULT_EXTRA_ARGS,
    private val defaultModel: String = DEFAULT_MODEL,
    private val workingDirectory: String? = null,
    private val environment: Map<String, String> = emptyMap(),
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : LlmBackend {

    override val id: String = ID

    override fun stream(request: LlmRequest): Flow<String> = flow {
        val exe = executable() ?: throw LlmException(AgenstormBundle.message("commit.backend.cli.notFound"))
        val command = GeneralCommandLine(exe, "-p", "--output-format", "text")
        val model = request.model?.takeIf { it.isNotBlank() } ?: defaultModel
        if (model.isNotBlank()) command.addParameters("--model", model)
        if (request.system.isNotBlank()) command.addParameters("--system-prompt", request.system)
        command.addParameters(ParametersListUtil.parse(extraArgs))
        workingDirectory?.let { command.withWorkDirectory(it) }
        command.withEnvironment(DEFAULT_ENVIRONMENT + environment)
        command.withCharset(Charsets.UTF_8)

        val output = run(command, request.user)
        if (output.isTimeout) throw LlmException(AgenstormBundle.message("commit.backend.cli.timeout", timeoutMs / 1000.0))
        if (output.exitCode != 0) {
            throw LlmException(output.stderr.trim().ifEmpty { AgenstormBundle.message("commit.backend.cli.exit", output.exitCode) })
        }
        val text = output.stdout.trim()
        if (text.isEmpty()) throw LlmException(AgenstormBundle.message("commit.backend.cli.noOutput"))
        emit(text)
    }

    override suspend fun validate(): String? = try {
        stream(LlmRequest(system = "", user = "Reply with the single word OK.", model = null, maxTokens = 8)).collect()
        null
    } catch (e: LlmException) {
        e.message
    }

    private suspend fun run(command: GeneralCommandLine, input: String): ProcessOutput {
        val handler = try {
            CapturingProcessHandler(command)
        } catch (e: ExecutionException) {
            throw LlmException(e.message ?: AgenstormBundle.message("commit.backend.cli.notFound"), e)
        }
        return coroutineScope {
            // runProcess() swallows thread interruption, so cancellation has to kill the process explicitly.
            val killer = launch {
                try {
                    awaitCancellation()
                } finally {
                    handler.destroyProcess()
                }
            }
            val output = withContext(Dispatchers.IO) {
                handler.processInput.use { it.write(input.toByteArray(Charsets.UTF_8)) }
                handler.runProcess(timeoutMs)
            }
            killer.cancel()
            output
        }
    }

    companion object {
        const val ID = "claude-cli"
        /** Alias the CLI resolves to the current Haiku; cheap and quick for commit messages. Empty leaves the choice to the CLI. */
        const val DEFAULT_MODEL = "haiku"
        /** No tools (the prompt already carries the diff), no session clutter, no MCP servers, no user customizations; editable in the settings. */
        const val DEFAULT_EXTRA_ARGS = "--tools \"\" --no-session-persistence --strict-mcp-config --safe-mode"
        /** Disables extended thinking, which Claude Code turns on by default; the `environment` parameter can override it. */
        val DEFAULT_ENVIRONMENT: Map<String, String> = mapOf("MAX_THINKING_TOKENS" to "0")
        const val DEFAULT_TIMEOUT_MS = 120_000

        private val FALLBACK_LOCATIONS = listOf(".local/bin/claude", ".claude/local/claude")
        private val SYSTEM_LOCATIONS = listOf("/opt/homebrew/bin/claude", "/usr/local/bin/claude")

        /** Configured path (must exist), else `claude` on the PATH, else the usual install locations. */
        fun discover(configuredPath: String?): String? {
            configuredPath?.trim()?.takeIf { it.isNotEmpty() }?.let { return if (File(it).canExecute()) it else null }
            PathEnvironmentVariableUtil.findInPath("claude")?.let { return it.path }
            val home = System.getProperty("user.home")
            return (FALLBACK_LOCATIONS.map { "$home/$it" } + SYSTEM_LOCATIONS).firstOrNull { File(it).canExecute() }
        }
    }
}
