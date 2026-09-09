package com.pronskiy.agenstorm.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.pronskiy.agenstorm.commit.CommitSettingsPanel
import com.pronskiy.agenstorm.frame.FrameTitleRefresher
import com.pronskiy.agenstorm.tabs.NativeTabStrip
import com.pronskiy.agenstorm.tabs.NativeTabsRegistryGuard
import com.pronskiy.agenstorm.tabs.ProjectTabsModel
import com.pronskiy.agenstorm.tabs.ProjectTabsWidgetInstaller
import com.pronskiy.agenstorm.terminal.OpenRequestServer
import kotlin.reflect.KMutableProperty1

/**
 * Settings → Tools → Agenstorm. One group per feature with its on/off switch; later epics add
 * their feature-specific controls to the matching group.
 */
class AgenstormConfigurable : BoundConfigurable(AgenstormBundle.message("settings.display.name")) {

    /** One entry of the commit backend selector; [toString] is what the combo box renders. */
    data class BackendOption(val id: String, val label: String) {
        override fun toString(): String = label
    }

    /** One entry of the live-markup reveal scope selector (ids as stored in the state; the feature reads them). */
    data class RevealScopeOption(val id: String, val label: String) {
        override fun toString(): String = label
    }

    private var commitPanel: CommitSettingsPanel? = null

    override fun createPanel(): DialogPanel = panel {
        row {
            comment(AgenstormBundle.message("settings.intro"))
        }
        featureGroup("settings.group.links", "settings.links.enabled", AgenstormSettings.State::linksEnabled)
        featureGroup("settings.group.frame", "settings.frame.hideFileName", AgenstormSettings.State::hideFileNameInTitle, onApply = FrameTitleRefresher::refreshOpenFrames)
        featureGroup("settings.group.commit", "settings.commit.enabled", AgenstormSettings.State::commitEnabled) {
            commitPanel = CommitSettingsPanel(ApplicationManager.getApplication().getService(AgenstormAppScope::class.java).scope).also { it.render(this) }
        }
        featureGroup("settings.group.tabs", "settings.tabs.enabled", AgenstormSettings.State::projectTabsEnabled, onApply = ::applyTabSettings) {
            row {
                checkBox(AgenstormBundle.message("settings.tabs.mirrorBounds"))
                    .bindSelected({ AgenstormSettings.getInstance().state.tabsMirrorWindowBounds }, { AgenstormSettings.getInstance().state.tabsMirrorWindowBounds = it })
                    .applyToComponent { name = "tabs.mirrorBounds" }
                    .comment(AgenstormBundle.message("settings.tabs.mirrorBounds.comment"))
            }
            row {
                checkBox(AgenstormBundle.message("settings.tabs.branchInStatusBar"))
                    .bindSelected({ AgenstormSettings.getInstance().state.branchInStatusBar }, { AgenstormSettings.getInstance().state.branchInStatusBar = it })
                    .onApply { AgenstormSettingsListener.fire() }
                    .applyToComponent { name = "tabs.branchInStatusBar" }
                    .comment(AgenstormBundle.message("settings.tabs.branchInStatusBar.comment"))
            }
            row {
                checkBox(AgenstormBundle.message("settings.tabs.showIcons"))
                    .bindSelected({ AgenstormSettings.getInstance().state.tabsShowIcons }, { AgenstormSettings.getInstance().state.tabsShowIcons = it })
                    .onApply { ProjectTabsModel.getInstance().refresh() }
                    .applyToComponent { name = "tabs.showIcons" }
            }
            row(AgenstormBundle.message("settings.tabs.maxWidth")) {
                intTextField(72..600, 10)
                    .bindIntText(MutableProperty({ AgenstormSettings.getInstance().state.tabsMaxWidth }, { AgenstormSettings.getInstance().state.tabsMaxWidth = it }))
                    .onApply { ProjectTabsModel.getInstance().refresh() }
                    .applyToComponent { name = "tabs.maxWidth" }
                    .comment(AgenstormBundle.message("settings.tabs.maxWidth.comment"))
            }
        }
        featureGroup("settings.group.markdown", "settings.markdown.liveMarkup.enabled", AgenstormSettings.State::liveMarkupEnabled, onApply = AgenstormSettingsListener::fire) {
            row {
                checkBox(AgenstormBundle.message("settings.markdown.checkboxes"))
                    .bindSelected({ AgenstormSettings.getInstance().state.liveMarkupCheckboxes }, { AgenstormSettings.getInstance().state.liveMarkupCheckboxes = it })
                    .onApply { AgenstormSettingsListener.fire() }
                    .applyToComponent { name = "markdown.checkboxes" }
            }
            row {
                checkBox(AgenstormBundle.message("settings.markdown.bullets"))
                    .bindSelected({ AgenstormSettings.getInstance().state.liveMarkupBullets }, { AgenstormSettings.getInstance().state.liveMarkupBullets = it })
                    .onApply { AgenstormSettingsListener.fire() }
                    .applyToComponent { name = "markdown.bullets" }
                    .comment(AgenstormBundle.message("settings.markdown.bullets.comment"))
            }
            row {
                checkBox(AgenstormBundle.message("settings.markdown.codeBlocks"))
                    .bindSelected({ AgenstormSettings.getInstance().state.liveMarkupCodeBlocks }, { AgenstormSettings.getInstance().state.liveMarkupCodeBlocks = it })
                    .onApply { AgenstormSettingsListener.fire() }
                    .applyToComponent { name = "markdown.codeBlocks" }
            }
            row {
                checkBox(AgenstormBundle.message("settings.markdown.blockQuotes"))
                    .bindSelected({ AgenstormSettings.getInstance().state.liveMarkupBlockQuotes }, { AgenstormSettings.getInstance().state.liveMarkupBlockQuotes = it })
                    .onApply { AgenstormSettingsListener.fire() }
                    .applyToComponent { name = "markdown.blockQuotes" }
            }
            row {
                checkBox(AgenstormBundle.message("settings.markdown.rules"))
                    .bindSelected({ AgenstormSettings.getInstance().state.liveMarkupRules }, { AgenstormSettings.getInstance().state.liveMarkupRules = it })
                    .onApply { AgenstormSettingsListener.fire() }
                    .applyToComponent { name = "markdown.rules" }
            }
            row(AgenstormBundle.message("settings.markdown.revealScope")) {
                comboBox(REVEAL_SCOPES)
                    .bindItem(
                        { REVEAL_SCOPES.firstOrNull { it.id == AgenstormSettings.getInstance().state.liveMarkupRevealScope } ?: REVEAL_SCOPES.first() },
                        { AgenstormSettings.getInstance().state.liveMarkupRevealScope = (it ?: REVEAL_SCOPES.first()).id },
                    )
                    .onApply { AgenstormSettingsListener.fire() }
                    .applyToComponent { name = "markdown.revealScope" }
                    .comment(AgenstormBundle.message("settings.markdown.revealScope.comment"))
            }
        }
        featureGroup("settings.group.terminal", "settings.terminal.open.enabled", AgenstormSettings.State::terminalOpenEnabled, onApply = ::applyTerminalSettings) {
            row(AgenstormBundle.message("settings.terminal.open.commandNames")) {
                textField()
                    .bindText({ AgenstormSettings.getInstance().state.terminalOpenCommandNames }, { AgenstormSettings.getInstance().state.terminalOpenCommandNames = it })
                    .align(AlignX.FILL)
                    .onApply { AgenstormSettingsListener.fire() }
                    .applyToComponent { name = "terminal.commandNames" }
                    .comment(AgenstormBundle.message("settings.terminal.open.commandNames.comment"))
            }
            row {
                checkBox(AgenstormBundle.message("settings.terminal.open.unknownFileTypes"))
                    .bindSelected({ AgenstormSettings.getInstance().state.terminalOpenUnknownFileTypes }, { AgenstormSettings.getInstance().state.terminalOpenUnknownFileTypes = it })
                    .onApply { AgenstormSettingsListener.fire() }
                    .applyToComponent { name = "terminal.unknownFileTypes" }
                    .comment(AgenstormBundle.message("settings.terminal.open.unknownFileTypes.comment"))
            }
        }
    }

    private companion object {
        /** Ids match `LiveMarkupController.SCOPE_*`; spelled out here because core/ must not load the Markdown feature. */
        val REVEAL_SCOPES = listOf(
            RevealScopeOption("element", AgenstormBundle.message("settings.markdown.revealScope.element")),
            RevealScopeOption("line", AgenstormBundle.message("settings.markdown.revealScope.line")),
        )
    }

    override fun disposeUIResources() {
        commitPanel?.dispose()
        commitPanel = null
        super.disposeUIResources()
    }

    private fun Panel.featureGroup(
        titleKey: String,
        toggleKey: String,
        toggle: KMutableProperty1<AgenstormSettings.State, Boolean>,
        onApply: (() -> Unit)? = null,
        extraRows: Panel.() -> Unit = {},
    ) {
        group(AgenstormBundle.message(titleKey)) {
            row {
                val cell = checkBox(AgenstormBundle.message(toggleKey))
                    // Read and write through the service on every access: loadState() may replace the State instance.
                    .bindSelected({ toggle.get(AgenstormSettings.getInstance().state) }, { toggle.set(AgenstormSettings.getInstance().state, it) })
                if (onApply != null) cell.onApply(onApply)
            }
            extraRows()
        }
    }

    /**
     * The tabs toggle changed: align the native-tabs registry key and re-render every strip. (A cell's apply
     * callback only runs when that cell changed, so the icon and width rows carry their own refresh.)
     */
    private fun applyTabSettings() {
        ProjectTabsWidgetInstaller.sync()
        NativeTabsRegistryGuard.syncFromSettings()
        NativeTabStrip.applyToAllProjects()
        ProjectTabsModel.getInstance().refresh()
        AgenstormSettingsListener.fire()
    }

    /**
     * The terminal toggle changed. Terminals that are already running keep the environment they started
     * with (the settings page says so); what the switch can do at once is unbind the endpoints, so nothing
     * is listening while the feature is off. [serviceIfCreated] so a project that never opened a terminal
     * does not get one created here.
     */
    private fun applyTerminalSettings() {
        if (!AgenstormSettings.getInstance().state.terminalOpenEnabled) {
            ProjectManager.getInstance().openProjects.forEach { it.serviceIfCreated<OpenRequestServer>()?.stop() }
        }
        AgenstormSettingsListener.fire()
    }
}
