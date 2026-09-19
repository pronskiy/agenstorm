package com.pronskiy.agenstorm.tabs.offload

import com.intellij.openapi.wm.IdeFrame
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.tabs.ProjectTabsModel
import java.lang.reflect.Proxy

/** Step P2.2: a frame coming to the front is what "active" means to the offload policy. */
class TabsFrameListenerTest : BasePlatformTestCase() {

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

    fun testActivatingAFrameTouchesItsProject() {
        val key = ProjectTabsModel.keyOf(project)
        assertNull(model.lastActive(key))

        TabsFrameListener(clock = { 42L }).onFrameActivated(frameOf(project))

        assertEquals(42L, model.lastActive(key))
    }

    fun testAFrameWithoutAProjectIsIgnored() {
        TabsFrameListener(clock = { 42L }).onFrameActivated(frameOf(null))
        assertTrue(model.state.lastActive.isEmpty())
    }

    private fun frameOf(project: com.intellij.openapi.project.Project?): IdeFrame = Proxy.newProxyInstance(
        IdeFrame::class.java.classLoader,
        arrayOf(IdeFrame::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getProject" -> project
            else -> throw UnsupportedOperationException(method.name)
        }
    } as IdeFrame
}
