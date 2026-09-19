package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.project.Project

/**
 * Step P2.1. One tab of the strip: a project that is open, or one Agenstorm offloaded — closed with everything
 * saved, remembered by its base path so a click can load it again. [key] is [ProjectTabsModel.keyOf] for both,
 * which is what keeps an offloaded project in its place in the stored order.
 */
sealed class ProjectTab {
    abstract val key: String
    abstract val name: String

    data class Loaded(val project: Project) : ProjectTab() {
        override val key: String get() = ProjectTabsModel.keyOf(project)
        override val name: String get() = project.name
    }

    /** [sinceMs] is when the project was offloaded (epoch millis), for the tooltip. */
    data class Offloaded(override val key: String, override val name: String, val sinceMs: Long) : ProjectTab()
}
