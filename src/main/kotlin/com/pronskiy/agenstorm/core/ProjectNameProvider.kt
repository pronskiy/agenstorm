package com.pronskiy.agenstorm.core

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * A better name for a project than its own, for the places Agenstorm shows project names — the project tabs and the
 * window title. The worktree feature (Epic T) names a linked worktree `<main checkout>:<worktree>`, since a worktree
 * is a project of its own whose name is just its folder's. An extension point of Agenstorm's own, so that `tabs/` and
 * `frame/` keep importing only `core/`.
 */
interface ProjectNameProvider {

    /** The name to show for [project], or null to leave it to the next provider or the project's own name. */
    fun displayName(project: Project): String?

    companion object {
        val EP_NAME: ExtensionPointName<ProjectNameProvider> = ExtensionPointName.create("com.pronskiy.agenstorm.projectNameProvider")

        fun displayName(project: Project): String =
            (if (project.isDisposed) null else EP_NAME.extensionList.firstNotNullOfOrNull { it.displayName(project) }) ?: project.name
    }
}
