package com.pronskiy.agenstorm.terminal.agents

import com.intellij.openapi.project.Project

/** Step X2.4. One running session in the sidebar: what Claude says about it, where it can be shown, and its dot. */
data class SessionRow(val session: LiveSession, val place: SessionPlace<ProjectTab>, val finished: Boolean) {

    /** The same row, pointing at no tab of [closing] — the model is app-wide and must not keep a closed project. */
    fun withoutTabOf(closing: Project?): SessionRow =
        if (closing != null && (place as? SessionPlace.InTab)?.tab?.project == closing) copy(place = SessionPlace.Elsewhere) else this

    /** The same row, pointing at no tab of a project that is gone. */
    fun withoutClosedTab(): SessionRow =
        if ((place as? SessionPlace.InTab)?.tab?.project?.isDisposed == true) copy(place = SessionPlace.Elsewhere) else this
}

/**
 * Step X1.2. One open project in the Agents sidebar: what it is called and where it lives — the base path is its
 * identity — and, from X2, the sessions running in it, and from X3 its past ones.
 */
data class ProjectGroup(
    val name: String,
    val basePath: String,
    val sessions: List<SessionRow> = emptyList(),
    /** Step X3.2: its newest past sessions, newest first. */
    val history: List<PastSession> = emptyList(),
) {

    companion object {
        /**
         * The groups in the order every window shows them: by name, case aside, then by path so two projects of the same
         * name keep their places; one group per base path.
         */
        fun sorted(groups: Collection<ProjectGroup>): List<ProjectGroup> =
            groups.distinctBy { it.basePath }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ProjectGroup::name).thenBy(ProjectGroup::basePath))
    }
}
