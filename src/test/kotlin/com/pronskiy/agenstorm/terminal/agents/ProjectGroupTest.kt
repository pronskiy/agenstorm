package com.pronskiy.agenstorm.terminal.agents

import org.junit.Assert.assertEquals
import org.junit.Test

/** Step X1.2: every window lists the projects in the same order. */
class ProjectGroupTest {

    @Test
    fun byNameCaseAsideThenByPath() {
        val groups = listOf(
            ProjectGroup("beta", "/w/beta"),
            ProjectGroup("Alpha", "/w/z/Alpha"),
            ProjectGroup("alpha", "/w/a/alpha"),
            ProjectGroup("Gamma", "/w/Gamma"),
        )

        assertEquals(listOf("/w/a/alpha", "/w/z/Alpha", "/w/beta", "/w/Gamma"), ProjectGroup.sorted(groups).map { it.basePath })
    }

    @Test
    fun theOrderDoesNotDependOnTheOrderProjectsOpened() {
        val groups = listOf(ProjectGroup("b", "/b"), ProjectGroup("a", "/a"), ProjectGroup("c", "/c"))

        assertEquals(ProjectGroup.sorted(groups), ProjectGroup.sorted(groups.reversed()))
    }

    @Test
    fun oneGroupPerBasePath() {
        val groups = listOf(ProjectGroup("app", "/w/app"), ProjectGroup("app", "/w/app"))

        assertEquals(1, ProjectGroup.sorted(groups).size)
    }
}
