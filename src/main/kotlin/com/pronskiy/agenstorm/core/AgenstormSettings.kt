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
