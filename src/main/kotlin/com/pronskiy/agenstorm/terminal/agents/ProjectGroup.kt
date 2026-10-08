package com.pronskiy.agenstorm.terminal.agents

/**
 * Step X1.2. One open project in the Agents sidebar: what it is called and where it lives; the base path is its identity.
 * The sessions under it come in X2.
 */
data class ProjectGroup(val name: String, val basePath: String) {

    companion object {
        /**
         * The groups in the order every window shows them: by name, case aside, then by path so two projects of the same
         * name keep their places; one group per base path.
         */
        fun sorted(groups: Collection<ProjectGroup>): List<ProjectGroup> =
            groups.distinctBy { it.basePath }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ProjectGroup::name).thenBy(ProjectGroup::basePath))
    }
}
