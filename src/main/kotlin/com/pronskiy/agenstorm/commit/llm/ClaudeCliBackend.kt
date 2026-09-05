package com.pronskiy.agenstorm.commit.llm

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.util.Key
import com.intellij.util.execution.ParametersListUtil
import com.pronskiy.agenstorm.core.AgenstormBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException

/**
 * The local `claude` CLI: `claude -p --output-format stream-json --verbose --include-partial-messages --model <model>
 * [--system-prompt …] <extra args>` with the user prompt on stdin. The model is the request's, else [defaultModel]
 * ([DEFAULT_MODEL] unless configured).
 *
 * Streaming: the CLI prints one JSON event per line; every `content_block_delta` with a `text_delta` is emitted as
 * it arrives, so the message grows in the commit field the same way it does for the HTTP backends. A CLI that sends
 * no partial messages still yields the final `result` text as one chunk. A `result` with `is_error` becomes an
 * [LlmException] carrying its message; a non-zero exit without one carries stderr (or whatever non-JSON text the CLI
 * printed, e.g. "Not logged in"). Cancelling the collector destroys the process. Flags that may drift between CLI
 * versions live in the "extra arguments" setting ([DEFAULT_EXTRA_ARGS]).
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

    override fun stream(request: LlmRequest): Flow<String> = channelFlow {
        val exe = executable() ?: throw LlmException(AgenstormBundle.message("commit.backend.cli.notFound"))
        val command = GeneralCommandLine(exe, "-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages")
        val model = request.model?.takeIf { it.isNotBlank() } ?: defaultModel
        if (model.isNotBlank()) command.addParameters("--model", model)
        if (request.system.isNotBlank()) command.addParameters("--system-prompt", request.system)
        command.addParameters(ParametersListUtil.parse(extraArgs))
        workingDirectory?.let { command.withWorkDirectory(it) }
        command.withEnvironment(DEFAULT_ENVIRONMENT + environment)
        command.withCharset(Charsets.UTF_8)

        val handler = try {
            OSProcessHandler(command)
        } catch (e: ExecutionException) {
            throw LlmException(e.message ?: AgenstormBundle.message("commit.backend.cli.notFound"), e)
        }
        val output = LineCollector()
        handler.addProcessListener(output)
        handler.startNotify()
        try {
            withContext(Dispatchers.IO) {
                try {
                    handler.processInput.use { it.write(request.user.toByteArray(Charsets.UTF_8)) }
                } catch (_: IOException) {
                    // The process is already gone; its exit code and stderr tell the story below.
                }
            }
            val parser = StreamJsonParser()
            val completed = withTimeoutOrNull(timeoutMs.toLong()) {
                for (line in output.lines) parser.accept(line)?.let { send(it) }
                true
            }
            if (completed == null) {
                handler.destroyProcess()
                throw LlmException(AgenstormBundle.message("commit.backend.cli.timeout", timeoutMs / 1000.0))
            }
            parser.finish(exitCode = handler.exitCode ?: -1, stderr = output.stderr())?.let { send(it) }
        } finally {
            if (!handler.isProcessTerminated) handler.destroyProcess()
        }
    }

    override suspend fun validate(): String? = try {
        stream(LlmRequest(system = "", user = "Reply with the single word OK.", model = null, maxTokens = 8)).collect()
        null
    } catch (e: LlmException) {
        e.message
    }

    /** Splits stdout into lines as it arrives (chunks are not line-aligned) and closes the channel on exit. */
    private class LineCollector : ProcessListener {
        val lines = Channel<String>(Channel.UNLIMITED)
        private val pending = StringBuilder()
        private val stderr = StringBuilder()

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            when {
                ProcessOutputType.isStdout(outputType) -> synchronized(pending) {
                    pending.append(event.text)
                    while (true) {
                        val newline = pending.indexOf("\n")
                        if (newline < 0) break
                        lines.trySend(pending.substring(0, newline))
                        pending.delete(0, newline + 1)
                    }
                }
                ProcessOutputType.isStderr(outputType) -> synchronized(stderr) { stderr.append(event.text) }
            }
        }

        override fun processTerminated(event: ProcessEvent) {
            synchronized(pending) {
                if (pending.isNotBlank()) lines.trySend(pending.toString())
                pending.setLength(0)
            }
            lines.close()
        }

        fun stderr(): String = synchronized(stderr) { stderr.toString() }
    }

    /** Understands the `stream-json` events: text deltas while running, the `result` at the end, anything else ignored. */
    private class StreamJsonParser {
        private var streamedText = false
        private var resultText: String? = null
        private var resultErrors: List<String> = emptyList()
        private var isError = false
        private val rawOutput = StringBuilder()

        /** Returns the text to emit for this line, if any. */
        fun accept(line: String): String? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            val event = parseObject(trimmed) ?: run { rawOutput.appendLine(trimmed); return null }
            when (event.string("type")) {
                "stream_event" -> {
                    val inner = event["event"] as? JsonObject ?: return null
                    if (inner.string("type") != "content_block_delta") return null
                    val delta = inner["delta"] as? JsonObject ?: return null
                    if (delta.string("type") != "text_delta") return null
                    val text = delta.string("text")?.takeIf { it.isNotEmpty() } ?: return null
                    streamedText = true
                    return text
                }
                "result" -> {
                    isError = (event["is_error"] as? JsonPrimitive)?.booleanOrNull == true
                    resultText = event.string("result")
                    resultErrors = (event["errors"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                }
            }
            return null
        }

        /** Throws for failures; returns the fallback text when the CLI streamed nothing but produced a result. */
        fun finish(exitCode: Int, stderr: String): String? {
            val problem = listOfNotNull(resultText?.trim()?.takeIf { it.isNotEmpty() }).ifEmpty { resultErrors }.joinToString("\n")
            if (isError) throw LlmException(problem.ifEmpty { failureMessage(exitCode, stderr) })
            if (exitCode != 0) throw LlmException(failureMessage(exitCode, stderr))
            if (streamedText) return null
            return resultText?.trim()?.takeIf { it.isNotEmpty() } ?: throw LlmException(AgenstormBundle.message("commit.backend.cli.noOutput"))
        }

        private fun failureMessage(exitCode: Int, stderr: String): String =
            stderr.trim().ifEmpty { rawOutput.toString().trim() }.ifEmpty { AgenstormBundle.message("commit.backend.cli.exit", exitCode) }

        private fun parseObject(line: String): JsonObject? {
            if (!line.startsWith("{")) return null
            return try {
                Json.parseToJsonElement(line) as? JsonObject
            } catch (_: SerializationException) {
                null
            }
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
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
