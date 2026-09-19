package com.pronskiy.agenstorm.tabs.offload

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.tabs.FakeProjectHolder
import com.pronskiy.agenstorm.tabs.ProjectTab
import com.pronskiy.agenstorm.tabs.ProjectTabsModel
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/** Step P2.6: a click on a dotted tab opens the project from its path; what cannot be opened says so. */
class ProjectLoaderTest : BasePlatformTestCase() {

    private lateinit var model: ProjectTabsModel
    private val opened = mutableListOf<Path>()
    private val balloons = mutableListOf<String>()
    private var openResult: Project? = null
    private var exists = true

    override fun setUp() {
        super.setUp()
        model = ProjectTabsModel.getInstance()
        model.loadState(ProjectTabsModel.State())
    }

    override fun tearDown() {
        try {
            model.loadState(ProjectTabsModel.State())
        } finally {
            super.tearDown()
        }
    }

    private fun loader() = ProjectLoader(
        model = model,
        open = { path -> opened.add(path); openResult },
        exists = { exists },
        notify = { balloons += it },
    )

    private fun offloadedBeta(): ProjectTab.Offloaded {
        model.markOffloaded(FakeProjectHolder.another(project, "beta"), now = 1L)
        return model.tabs().filterIsInstance<ProjectTab.Offloaded>().single()
    }

    fun testLoadOpensTheProjectAtTheTabsPath() {
        val tab = offloadedBeta()
        openResult = FakeProjectHolder.another(project, "beta")

        val result = runBlocking { loader().load(tab) }

        assertSame(openResult, result)
        assertEquals(listOf(Path.of("/fake/beta")), opened)
        assertTrue(balloons.isEmpty())
    }

    fun testAMissingDirectoryForgetsTheTabAndSaysSo() {
        val tab = offloadedBeta()
        exists = false

        assertNull(runBlocking { loader().load(tab) })

        assertTrue("nothing was opened", opened.isEmpty())
        assertTrue(model.state.offloaded.isEmpty())
        assertEquals(1, balloons.size)
        assertTrue(balloons.single(), "beta" in balloons.single() && "/fake/beta" in balloons.single())
    }

    fun testAnOpenThatReturnsNothingKeepsTheTabAndSaysSo() {
        val tab = offloadedBeta()
        openResult = null

        assertNull(runBlocking { loader().load(tab) })

        assertEquals(1, opened.size)
        assertEquals("the tab stays so the click can be repeated", 1, model.state.offloaded.size)
        assertEquals(1, balloons.size)
    }
}
