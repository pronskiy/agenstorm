package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

/** Steps E1.2 / P2.1: tab order by project key, persisted; offloaded tabs and activity alongside; listeners on the EDT. */
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
        val received = mutableListOf<List<ProjectTab>>()
        val disposable = Disposer.newDisposable()
        model.addListener({ received += it }, disposable)

        model.projectOpened(project)
        assertEquals(1, received.size)
        assertTrue(received.single().any { it is ProjectTab.Loaded && it.project == project })
        assertTrue(ProjectTabsModel.keyOf(project) in model.state.order)
        assertEquals(model.tabs(), received.single())

        Disposer.dispose(disposable)
        model.projectClosed(project)
        assertEquals(1, received.size)
    }

    /** Step P2.1: an offloaded project keeps a tab, in stored order, until it is loaded again or forgotten. */
    fun testAnOffloadedProjectKeepsItsTabInStoredOrder() {
        val beta = FakeProjectHolder.another(project, "beta")
        val mine = ProjectTabsModel.keyOf(project)
        model.loadState(ProjectTabsModel.State().apply { order = mutableListOf("/fake/beta", mine) })

        model.markOffloaded(beta, now = 1_000L)

        val tabs = model.tabs()
        assertEquals(listOf("/fake/beta", mine), tabs.map { it.key })
        val offloaded = tabs.first() as ProjectTab.Offloaded
        assertEquals("beta", offloaded.name)
        assertEquals(1_000L, offloaded.sinceMs)
        assertEquals(project, (tabs.last() as ProjectTab.Loaded).project)

        // Loaded again (the platform opens it, TabsStartupActivity reports it): the entry goes, the order stays.
        model.projectOpened(beta, now = 2_000L)
        assertTrue(model.state.offloaded.isEmpty())
        assertEquals(2_000L, model.lastActive("/fake/beta"))
        assertEquals(listOf("/fake/beta", mine), model.state.order)
    }

    fun testUnmarkAndForgetRemoveTheEntryAndListenersSeeProjectTabs() {
        val received = mutableListOf<List<ProjectTab>>()
        model.addListener({ received += it }, testRootDisposable)
        val beta = FakeProjectHolder.another(project, "beta")

        model.markOffloaded(beta, now = 1L)
        assertTrue(received.last().any { it is ProjectTab.Offloaded && it.key == "/fake/beta" })
        model.unmarkOffloaded("/fake/beta")
        assertTrue(received.last().none { it.key == "/fake/beta" })

        model.markOffloaded(beta, now = 1L)
        model.forget("/fake/beta")
        assertTrue(model.state.offloaded.isEmpty())
        assertTrue(received.last().none { it.key == "/fake/beta" })
        assertEquals(4, received.size)
    }

    fun testAtMostTwelveOffloadedTabsTheOldestForgottenFirst() {
        for (i in 1..ProjectTabsModel.MAX_OFFLOADED + 1) model.markOffloaded(FakeProjectHolder.another(project, "p$i"), now = i.toLong())
        assertEquals(ProjectTabsModel.MAX_OFFLOADED, model.state.offloaded.size)
        assertFalse("the oldest goes first", model.state.offloaded.any { it.key == "/fake/p1" })
        assertTrue(model.state.offloaded.any { it.key == "/fake/p${ProjectTabsModel.MAX_OFFLOADED + 1}" })
    }

    fun testTouchRecordsWhenAProjectWasLastActive() {
        assertNull(model.lastActive("/fake/x"))
        model.touch("/fake/x", now = 5L)
        assertEquals(5L, model.lastActive("/fake/x"))
        model.touch("/fake/x", now = 9L)
        assertEquals(9L, model.lastActive("/fake/x"))
    }

    fun testOffloadedEntriesAndActivitySurviveSerialization() {
        model.markOffloaded(FakeProjectHolder.another(project, "beta"), now = 7L)
        model.touch("/fake/beta", now = 3L)
        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(model.state), ProjectTabsModel.State::class.java)
        assertEquals("beta", restored.offloaded.single().name)
        assertEquals(7L, restored.offloaded.single().since)
        assertEquals(3L, restored.lastActive["/fake/beta"])
    }

    fun testMoveTabWorksByKeyAcrossLoadedAndOffloadedTabs() {
        val mine = ProjectTabsModel.keyOf(project)
        model.markOffloaded(FakeProjectHolder.another(project, "beta"), now = 1L)
        model.projectOpened(project)
        assertEquals(listOf("/fake/beta", mine), model.tabs().map { it.key })

        model.moveTab("/fake/beta", 1)
        assertEquals(listOf(mine, "/fake/beta"), model.tabs().map { it.key })
    }

    fun testListenersAreCalledOnTheEdtEvenWhenTheChangeComesFromAnotherThread() {
        var calledOnEdt: Boolean? = null
        model.addListener({ calledOnEdt = ApplicationManager.getApplication().isDispatchThread }, testRootDisposable)

        ApplicationManager.getApplication().executeOnPooledThread { model.projectOpened(project) }.get()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(true, calledOnEdt)
    }
}
