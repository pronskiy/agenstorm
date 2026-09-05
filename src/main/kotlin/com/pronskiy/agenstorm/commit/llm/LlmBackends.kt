package com.pronskiy.agenstorm.commit.llm

import com.pronskiy.agenstorm.core.AgenstormSettings

/** Maps the `commitBackendId` setting to a backend instance; null means "not configured" and disables the action. */
object LlmBackends {

    fun forId(id: String, state: AgenstormSettings.State = AgenstormSettings.getInstance().state): LlmBackend? = when (id.trim()) {
        AnthropicBackend.ID -> AnthropicBackend(apiKey = { ApiKeyStore.get(AnthropicBackend.ID) })
        OpenAiCompatibleBackend.ID -> OpenAiCompatibleBackend(
            apiKey = { ApiKeyStore.get(OpenAiCompatibleBackend.ID) },
            baseUrl = state.commitOpenAiBaseUrl.ifBlank { OpenAiCompatibleBackend.DEFAULT_BASE_URL },
            defaultModel = state.commitModel,
        )
        FakeBackend.ID -> FakeBackend()
        else -> null
    }
}
