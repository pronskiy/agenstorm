package com.pronskiy.agenstorm.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level settings for every Agenstorm feature, persisted to `agenstorm.xml`.
 *
 * Each feature package reads its own toggle from [state] and must behave like a no-op when it is off.
 * Feature-specific fields (allow-lists, prompts, model ids) are added to [State] by the epic that needs them.
 */
@Service(Service.Level.APP)
@State(name = "Agenstorm", storages = [Storage("agenstorm.xml")], category = SettingsCategory.TOOLS)
class AgenstormSettings : PersistentStateComponent<AgenstormSettings.State> {

    data class State(
        /** Epic A: clickable `path:line[:col]` locations in Markdown, comments and PHP strings. */
        var linksEnabled: Boolean = true,
        /** Epic B: New Scratch File popup limited to an allow-list of file types. */
        var scratchFilterEnabled: Boolean = true,
        /** Epic C: window title shows the project only, never the current file. */
        var hideFileNameInTitle: Boolean = true,
        /** Epic D: AI commit message generation in the commit toolbar. */
        var commitEnabled: Boolean = true,
        /** Epic E: project tabs rendered inside the main toolbar. */
        var projectTabsEnabled: Boolean = true,
        /** Epic F: Obsidian-style live markup for Markdown. */
        var liveMarkupEnabled: Boolean = true,
        /** Epic B: internal `FileType.name`s that stay in the New Scratch File popup when the filter is on. */
        var scratchAllowedFileTypes: MutableList<String> = mutableListOf("PLAIN_TEXT", "Markdown", "PHP", "JavaScript"),
        /** Epic D: backend id (`anthropic`, `openai`, `claude-cli`, `fake`); an unknown id disables the action. */
        var commitBackendId: String = "anthropic",
        /** Epic D: model id passed to the backend; empty = the backend's default (required for `openai`). */
        var commitModel: String = "",
        /** Epic D: base URL of the OpenAI-compatible endpoint (OpenAI, Ollama, LM Studio, OpenRouter, Groq). */
        var commitOpenAiBaseUrl: String = "https://api.openai.com/v1",
        /** Epic D: path to the `claude` executable; empty = PATH lookup plus the usual install locations. */
        var commitClaudeCliPath: String = "",
        /** Epic D: extra `claude -p` arguments; flags that may drift between CLI versions live here, not in code. */
        var commitClaudeCliExtraArgs: String = "--tools \"\" --no-session-persistence --strict-mcp-config --safe-mode",
        /** Epic D: budget for the unified diff sent to the model, in characters. */
        var commitMaxDiffChars: Int = 60_000,
        var commitConventionalCommits: Boolean = true,
        var commitBodyEnabled: Boolean = true,
        /** Epic D: language the message is written in; empty = the model's default (English). */
        var commitLanguage: String = "",
        /** Epic D: prompt templates; empty = the built-in templates in `resources/prompts/`. */
        var commitSystemPrompt: String = "",
        var commitUserPrompt: String = "",
        /** Epic E: set when Agenstorm turned the native macOS project tabs off, so turning the feature off restores them; a user's own registry choice is left alone. */
        var nativeTabsDisabledByAgenstorm: Boolean = false,
        /** Epic E: switching tabs moves the target window onto the current one's bounds (skipped in full screen). */
        var tabsMirrorWindowBounds: Boolean = true,
        /** Epic E: project icons on the tabs. */
        var tabsShowIcons: Boolean = true,
        /** Epic E: widest a tab may grow before the name is ellipsized, in pixels (72–600). */
        var tabsMaxWidth: Int = 220,
        /** Epic E (Phase E3): the Git branch leaves the toolbar and shows bottom-left in the status bar. */
        var branchInStatusBar: Boolean = true,
        /** Epic E (Phase E3): set when Agenstorm hid the bottom navigation bar, so turning the feature off restores it. */
        var navBarHiddenByAgenstorm: Boolean = false,
        /** Epic F: task checkboxes render as ☐ / ☑ and toggle on click. */
        var liveMarkupCheckboxes: Boolean = true,
        /** Epic F: list bullets `-` / `*` / `+` render as •. */
        var liveMarkupBullets: Boolean = true,
    )

    private var currentState = State()

    override fun getState(): State = currentState

    override fun loadState(state: State) {
        currentState = state
    }

    companion object {
        @JvmStatic
        fun getInstance(): AgenstormSettings =
            ApplicationManager.getApplication().getService(AgenstormSettings::class.java)
    }
}
