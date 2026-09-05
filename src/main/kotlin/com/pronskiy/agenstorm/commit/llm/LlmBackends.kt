package com.pronskiy.agenstorm.commit.llm

/** Maps the `commitBackendId` setting to a backend instance; null means "not configured" and disables the action. */
object LlmBackends {

    fun forId(id: String): LlmBackend? = when (id.trim()) {
        FakeBackend.ID -> FakeBackend()
        else -> null
    }
}
