package com.pronskiy.agenstorm.tabs.offload

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.tabs.FakeProjectHolder
import com.pronskiy.agenstorm.tabs.ProjectTabsModel
import com.pronskiy.agenstorm.tabs.ProjectTabsModel.Companion.keyOf
import com.pronskiy.agenstorm.tabs.offload.Offloader.Reason

/** Step P2.5: the sweep — mark, close, and take the mark back when the close did not happen. */
class OffloaderTest : BasePlatformTestCase() {

    private val hour = 3_600_000L
    private val now = 100 * hour

    private lateinit var model: ProjectTabsModel
    private val closed = mutableListOf<String>()
    private val notices = mutableListOf<Pair<String, Reason>>()
    private var closeResult = true
    private var busy: (Project) -> String? = { null }
    private var settings = OffloadSettings(enabled = true, idleMs = 2 * hour, maxLoaded = 8)
    private var active: String? = null

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

    private fun offloader() = Offloader(
        model = model,
        settings = { settings },
        activeKey = { active },
        busyReason = { busy(it) },
        close = { closed += keyOf(it); closeResult },
        clock = { now },
        onOffloaded = { name, reason -> notices += name to reason },
    )

    private fun fake(name: String, activeMinutesAgo: Long): Project {
        val project = FakeProjectHolder.another(project, name)
        model.touch(keyOf(project), now - activeMinutesAgo * 60_000L)
        return project
    }

    fun testIdleProjectsAreClosedAndKeptAsOffloadedTabs() {
        val a = fake("a", 0)
        val b = fake("b", 180)
        val c = fake("c", 300)
        active = keyOf(a)

        val result = offloader().sweep(listOf(a, b, c))

        assertEquals(listOf("/fake/c", "/fake/b"), result)
        assertEquals(listOf("/fake/c", "/fake/b"), closed)
        assertEquals(setOf("/fake/c", "/fake/b"), model.state.offloaded.map { it.key }.toSet())
        assertEquals(listOf("c" to Reason.IDLE, "b" to Reason.IDLE), notices)
    }

    fun testARefusedCloseTakesTheTabBack() {
        val a = fake("a", 0)
        val b = fake("b", 300)
        active = keyOf(a)
        closeResult = false

        assertEquals(emptyList<String>(), offloader().sweep(listOf(a, b)))

        assertEquals(listOf("/fake/b"), closed)
        assertTrue("no offloaded tab for a project that is still open", model.state.offloaded.isEmpty())
        assertTrue(notices.isEmpty())
    }

    fun testAGuardThatObjectsRightBeforeTheCloseSkipsTheProject() {
        val a = fake("a", 0)
        val b = fake("b", 300)
        active = keyOf(a)
        var asked = 0
        busy = { if (it.name == "b" && ++asked >= 2) "a command just started" else null }

        assertEquals(emptyList<String>(), offloader().sweep(listOf(a, b)))

        assertTrue(closed.isEmpty())
        assertTrue(model.state.offloaded.isEmpty())
    }

    fun testBeyondTheCapTheLeastRecentlyActiveGoesWithTheCapReason() {
        settings = settings.copy(maxLoaded = 2)
        val a = fake("a", 0)
        val b = fake("b", 30)
        val c = fake("c", 90)
        active = keyOf(a)

        assertEquals(listOf("/fake/c"), offloader().sweep(listOf(a, b, c)))
        assertEquals(listOf("c" to Reason.CAP), notices)
    }

    fun testDisabledClosesNothing() {
        settings = settings.copy(enabled = false)
        val a = fake("a", 0)
        val b = fake("b", 3000)
        active = keyOf(a)

        assertEquals(emptyList<String>(), offloader().sweep(listOf(a, b)))
        assertTrue(closed.isEmpty())
    }
}
