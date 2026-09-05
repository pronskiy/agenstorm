package com.pronskiy.agenstorm.commit.llm

import kotlinx.coroutines.flow.Flow

/** One generation request: a system prompt, a user prompt, an optional model id and a token budget. */
data class LlmRequest(
    val system: String,
    val user: String,
    val model: String? = null,
    val maxTokens: Int = 1024,
)

/**
 * A streaming text backend. Implementations: Anthropic Messages API, any OpenAI-compatible endpoint,
 * the local `claude` CLI, and [FakeBackend] for tests.
 */
interface LlmBackend {

    /** Stable id stored in the settings: `anthropic`, `openai`, `claude-cli`, `fake`. */
    val id: String

    /**
     * Emits text deltas in order and completes normally at the end of the stream. Failures surface as
     * [LlmException] with a message fit for a notification balloon. Cancelling the collector must stop the
     * underlying request or process.
     */
    fun stream(request: LlmRequest): Flow<String>

    /** Null when the backend is usable, otherwise a problem description for the settings page "Test" button. */
    suspend fun validate(): String?
}

class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
