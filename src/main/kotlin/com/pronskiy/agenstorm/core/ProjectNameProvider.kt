package com.pronskiy.agenstorm.core

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * A better name for a project than its own, for the places Agenstorm shows project names — the project tabs, the "+"
 * popup's recent projects and the window title. The worktree feature (Epic T) names a linked worktree
 * `<main checkout>:<worktree>`, since a worktree is a project of its own whose name is just its folder's, and gives it
 * its main checkout's icon. An extension point of Agenstorm's own, so that `tabs/` and `frame/` keep importing only
 * `core/`. Asked by folder, since a recent project in the popup is no more than one.
 */
interface ProjectNameProvider {

    /** The name to show for the project in [basePath], or null to leave it to the next provider or the project's own name. */
    fun displayName(basePath: String): String?

    /** The folder of the project whose icon the one in [basePath] shows — a linked worktree's main checkout — or null for its own. */
    fun iconPath(basePath: String): String? = null

    companion object {
        val EP_NAME: ExtensionPointName<ProjectNameProvider> = ExtensionPointName.create("com.pronskiy.agenstorm.projectNameProvider")

        fun displayName(project: Project): String =
            (if (project.isDisposed) null else project.basePath?.let(::nameOf)) ?: project.name

        /** The name a provider gives the project in [basePath]; null when none renames it. */
        fun nameOf(basePath: String): String? = EP_NAME.extensionList.firstNotNullOfOrNull { it.displayName(basePath) }

        /** The folder whose project icon stands for the project in [basePath]; null when it shows its own. */
        fun iconPathOf(basePath: String): String? = EP_NAME.extensionList.firstNotNullOfOrNull { it.iconPath(basePath) }
    }
}
