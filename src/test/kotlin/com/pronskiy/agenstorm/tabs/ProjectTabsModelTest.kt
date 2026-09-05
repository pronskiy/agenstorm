package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Step E1.2: tab order by project key, persisted; listeners on the EDT. */
class ProjectTabsModelTest : BasePlatformTestCase() {

    private lateinit var model: ProjectTabsModel

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

    fun testStateIsStoredInAgenstormTabsXml() {
        val annotation = ProjectTabsModel::class.java.getAnnotation(State::class.java)
        assertEquals("AgenstormProjectTabs", annotation!!.name)
        assertEquals(listOf("agenstorm-tabs.xml"), annotation.storages.map(Storage::value))
    }

    fun testOpenProjectsFollowTheStoredOrderAndUnknownOnesAreAppended() {
        model.loadState(ProjectTabsModel.State().apply { order = mutableListOf("/b", "/closed", "/a") })
        assertEquals(listOf("/b", "/a", "/c", "/d"), model.sortKeys(listOf("/a", "/c", "/b", "/d")))
    }

    fun testRememberAppendsNewKeysOnceAndForgetsOldClosedOnesBeyondTheCap() {
        model.remember("/a", listOf("/a"))
        model.remember("/a", listOf("/a"))
        model.remember("/b", listOf("/a", "/b"))
        assertEquals(listOf("/a", "/b"), model.state.order)

        model.loadState(ProjectTabsModel.State().apply { order = (1..ProjectTabsModel.MAX_REMEMBERED).map { "/old$it" }.toMutableList() })
        model.remember("/new", listOf("/old7", "/new"))
        assertEquals(ProjectTabsModel.MAX_REMEMBERED, model.state.order.size)
        assertFalse("the oldest closed key goes first", "/old1" in model.state.order)
        assertTrue("an open key is never forgotten", "/old7" in model.state.order)
        assertEquals("/new", model.state.order.last())
    }

    fun testMoveKeyReordersOpenTabsAndKeepsClosedKeysBehindThem() {
        model.loadState(ProjectTabsModel.State().apply { order = mutableListOf("/a", "/z-closed", "/b", "/c") })
        model.moveKey("/c", 0, listOf("/a", "/b", "/c"))
        assertEquals(listOf("/c", "/a", "/b", "/z-closed"), model.state.order)

        model.moveKey("/c", 99, listOf("/a", "/b", "/c"))
        assertEquals(listOf("/a", "/b", "/c", "/z-closed"), model.state.order)

        model.moveKey("/unknown", 0, listOf("/a", "/b", "/c"))
        assertEquals(listOf("/a", "/b", "/c", "/z-closed"), model.state.order)
    }

    fun testTabsContainTheOpenProjectAndListenersStopAfterDispose() {
        val received = mutableListOf<List<Project>>()
        val disposable = Disposer.newDisposable()
        model.addListener({ received += it }, disposable)

        model.projectOpened(project)
        assertEquals(1, received.size)
        assertTrue(project in received.single())
        assertTrue(ProjectTabsModel.keyOf(project) in model.state.order)
        assertEquals(model.tabs(), received.single())

        Disposer.dispose(disposable)
        model.projectClosed(project)
        assertEquals(1, received.size)
    }

    fun testListenersAreCalledOnTheEdtEvenWhenTheChangeComesFromAnotherThread() {
        var calledOnEdt: Boolean? = null
        model.addListener({ calledOnEdt = ApplicationManager.getApplication().isDispatchThread }, testRootDisposable)

        ApplicationManager.getApplication().executeOnPooledThread { model.projectOpened(project) }.get()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(true, calledOnEdt)
    }
}
