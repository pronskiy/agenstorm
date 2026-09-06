package com.pronskiy.agenstorm.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.pronskiy.agenstorm.commit.CommitSettingsPanel
import com.pronskiy.agenstorm.frame.FrameTitleRefresher
import com.pronskiy.agenstorm.tabs.NativeTabsRegistryGuard
import com.pronskiy.agenstorm.tabs.ProjectTabsModel
import javax.swing.JComponent
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

    private var allowListArea: JBTextArea? = null
    private var commitPanel: CommitSettingsPanel? = null

    override fun createPanel(): DialogPanel = panel {
        row {
            comment(AgenstormBundle.message("settings.intro"))
        }
        featureGroup("settings.group.links", "settings.links.enabled", AgenstormSettings.State::linksEnabled)
        featureGroup("settings.group.scratch", "settings.scratch.enabled", AgenstormSettings.State::scratchFilterEnabled) {
            row {
                allowListArea = textArea()
                    .rows(4)
                    .align(AlignX.FILL)
                    .bindText(
                        { formatAllowList(AgenstormSettings.getInstance().state.scratchAllowedFileTypes) },
                        { text -> applyAllowList(text) },
                    )
                    .comment(AgenstormBundle.message("settings.scratch.allowList.comment"))
                    .applyToComponent { name = "scratch.allowList" }
                    .component
            }
            row {
                button(AgenstormBundle.message("settings.scratch.addCurrentFileType")) {
                    currentFileType()?.let { addFileTypeName(it.name) }
                }
                button(AgenstormBundle.message("settings.scratch.addFileType")) { event ->
                    chooseFileType(event.source as? JComponent)
                }
            }
        }
        featureGroup("settings.group.frame", "settings.frame.hideFileName", AgenstormSettings.State::hideFileNameInTitle, onApply = FrameTitleRefresher::refreshOpenFrames)
        featureGroup("settings.group.commit", "settings.commit.enabled", AgenstormSettings.State::commitEnabled) {
            commitPanel = CommitSettingsPanel(ApplicationManager.getApplication().getService(AgenstormAppScope::class.java).scope).also { it.render(this) }
        }
        featureGroup("settings.group.tabs", "settings.tabs.enabled", AgenstormSettings.State::projectTabsEnabled, onApply = ::applyTabSettings) {
            row {
                checkBox(AgenstormBundle.message("settings.tabs.mirrorBounds"))
                    .bindSelected({ AgenstormSettings.getInstance().state.tabsMirrorWindowBounds }, { AgenstormSettings.getInstance().state.tabsMirrorWindowBounds = it })
                    .applyToComponent { name = "tabs.mirrorBounds" }
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
        }
    }

    override fun disposeUIResources() {
        allowListArea = null
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
        NativeTabsRegistryGuard.syncFromSettings()
        ProjectTabsModel.getInstance().refresh()
        AgenstormSettingsListener.fire()
    }

    /** The file type of the file selected in the most recently opened project's editor, if any. */
    private fun currentFileType(): FileType? =
        ProjectManager.getInstance().openProjects.asSequence()
            .flatMap { FileEditorManager.getInstance(it).selectedFiles.asSequence() }
            .map { it.fileType }
            .firstOrNull()

    private fun chooseFileType(anchor: JComponent?) {
        val names = FileTypeManager.getInstance().registeredFileTypes.map { it.name }.filter { it.isNotBlank() }.sorted()
        val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(names)
            .setTitle(AgenstormBundle.message("settings.scratch.chooseFileType.title"))
            .setNamerForFiltering { it }
            .setItemChosenCallback { addFileTypeName(it) }
            .createPopup()
        if (anchor != null) popup.showUnderneathOf(anchor) else popup.showInFocusCenter()
    }

    /** Stores the parsed list and shows it normalized, so the panel is no longer "modified" right after Apply. */
    private fun applyAllowList(text: String) {
        val names = parseAllowList(text)
        AgenstormSettings.getInstance().state.scratchAllowedFileTypes = names
        allowListArea?.let { area ->
            val normalized = formatAllowList(names)
            if (area.text != normalized) area.text = normalized
        }
    }

    private fun addFileTypeName(name: String) {
        val area = allowListArea ?: return
        val names = parseAllowList(area.text)
        if (name !in names) area.text = formatAllowList(names + name)
    }
}
