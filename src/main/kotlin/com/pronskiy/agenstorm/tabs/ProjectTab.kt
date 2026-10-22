package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.project.Project
import com.pronskiy.agenstorm.core.ProjectNameProvider

/**
 * Step P2.1. One tab of the strip: a project that is open, or one Agenstorm offloaded — closed with everything
 * saved, remembered by its base path so a click can load it again. [key] is [ProjectTabsModel.keyOf] for both,
 * which is what keeps an offloaded project in its place in the stored order. A tab's [name] is the project's display
 * name ([ProjectNameProvider]) — `app:fix-login` for a linked worktree. Its [label] is what the tab shows: the name,
 * or the name with parent folders in front when another tab of the strip shares it ([ProjectTabsModel.tabs]); a
 * worktree's [part], `fix-login`, ends it after a colon and is drawn dimmed. The project icon is made from [name], so a
 * prefix never changes the icon's letters.
 */
sealed class ProjectTab {
    abstract val key: String
    abstract val name: String
    abstract val label: String
    abstract val part: String?

    data class Loaded(
        val project: Project,
        override val label: String = ProjectNameProvider.displayName(project),
        override val part: String? = ProjectNameProvider.nameOf(project).part,
    ) : ProjectTab() {
        override val key: String get() = ProjectTabsModel.keyOf(project)
        override val name: String get() = ProjectNameProvider.displayName(project)
    }

    /** [sinceMs] is when the project was offloaded (epoch millis), for the tooltip. */
    data class Offloaded(
        override val key: String,
        override val name: String,
        val sinceMs: Long,
        override val label: String = name,
        override val part: String? = null,
    ) : ProjectTab()
}
