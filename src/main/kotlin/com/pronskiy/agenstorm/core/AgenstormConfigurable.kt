package com.pronskiy.agenstorm.core

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import kotlin.reflect.KMutableProperty1

/**
 * Settings → Tools → Agenstorm. One group per feature with its on/off switch; later epics add
 * their feature-specific controls to the matching group.
 */
class AgenstormConfigurable : BoundConfigurable(AgenstormBundle.message("settings.display.name")) {

    override fun createPanel(): DialogPanel = panel {
        row {
            comment(AgenstormBundle.message("settings.intro"))
        }
        featureGroup("settings.group.links", "settings.links.enabled", AgenstormSettings.State::linksEnabled)
        featureGroup("settings.group.scratch", "settings.scratch.enabled", AgenstormSettings.State::scratchFilterEnabled)
        featureGroup("settings.group.frame", "settings.frame.hideFileName", AgenstormSettings.State::hideFileNameInTitle)
        featureGroup("settings.group.commit", "settings.commit.enabled", AgenstormSettings.State::commitEnabled)
        featureGroup("settings.group.tabs", "settings.tabs.enabled", AgenstormSettings.State::projectTabsEnabled)
        featureGroup("settings.group.markdown", "settings.markdown.liveMarkup.enabled", AgenstormSettings.State::liveMarkupEnabled)
    }

    private fun Panel.featureGroup(
        titleKey: String,
        toggleKey: String,
        toggle: KMutableProperty1<AgenstormSettings.State, Boolean>,
    ) {
        group(AgenstormBundle.message(titleKey)) {
            row {
                checkBox(AgenstormBundle.message(toggleKey))
                    // Read and write through the service on every access: loadState() may replace the State instance.
                    .bindSelected({ toggle.get(AgenstormSettings.getInstance().state) }, { toggle.set(AgenstormSettings.getInstance().state, it) })
            }
        }
    }
}
