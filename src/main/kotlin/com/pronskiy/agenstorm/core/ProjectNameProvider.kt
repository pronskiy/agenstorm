package com.pronskiy.agenstorm.core

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * A better name for a project than its own, for the places Agenstorm shows project names — the project tabs, the "+"
 * popup's recent projects and the window title. The worktree feature (Epic T) names a linked worktree
 * `<main checkout>:<worktree>`, since a worktree is a project of its own whose name is just its folder's, and places
 * it in its main checkout's folder, whose icon it shows and whose parent tells it apart from a same-named project. An
 * extension point of Agenstorm's own, so that `tabs/` and `frame/` keep importing only `core/`. Asked by folder, since
 * a recent project in the popup is no more than one.
 */
interface ProjectNameProvider {

    /** The name of the project in [basePath], or null to leave it to the next provider or the project's own. */
    fun name(basePath: String): ProjectName?

    companion object {
        val EP_NAME: ExtensionPointName<ProjectNameProvider> = ExtensionPointName.create("com.pronskiy.agenstorm.projectNameProvider")

        /** What [project] is called: a provider's name, or its own in its own folder. */
        fun nameOf(project: Project): ProjectName =
            (if (project.isDisposed) null else project.basePath?.let(::nameAt)) ?: ProjectName(project.name, project.basePath)

        fun displayName(project: Project): String = nameOf(project).text

        /** A provider's name for the project in [basePath]; null when none renames it. */
        fun nameAt(basePath: String): ProjectName? = EP_NAME.extensionList.firstNotNullOfOrNull { it.name(basePath) }
    }
}

/**
 * A project's name as Agenstorm shows it, [text]: [project], the name of the project it belongs to, kept in
 * [projectPath], and [part], what tells it apart within that one — the worktree `fix-login` of `app` is
 * `app:fix-login`, in `app`'s folder. A project and its worktrees share [projectPath], so the icon and the folder that
 * tells same-named projects apart are that project's.
 */
data class ProjectName(val project: String, val projectPath: String?, val part: String? = null) {
    val text: String get() = if (part == null) project else "$project:$part"
}
