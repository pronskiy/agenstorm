package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.tabs.ui.ProjectTabsPanel
import java.awt.Component
import java.awt.event.MouseEvent

/** Step E1.3: the strip renders the model, highlights the frame's project and reports clicks through callbacks. */
class ProjectTabsPanelTest : BasePlatformTestCase() {

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

    fun testRendersOneTabPerOpenProjectAndHighlightsTheOwner() {
        val panel = ProjectTabsPanel(model)
        panel.attach()
        try {
            val labels = panel.tabLabels()
            assertEquals(model.tabs().size, labels.size)
            val mine = labels.single { it.project === project }
            assertEquals(project.name, mine.title)
            assertEquals(project.basePath, mine.toolTipText)
            assertFalse(mine.isSelected)

            panel.ownerProject = project
            assertTrue(panel.tabLabels().single { it.project === project }.isSelected)

            // A model change rebuilds the strip and keeps the owner highlighted.
            model.projectOpened(project)
            val rebuilt = panel.tabLabels()
            assertEquals(labels.size, rebuilt.size)
            assertTrue(rebuilt.single { it.project === project }.isSelected)
        } finally {
            panel.detach()
        }
    }

    fun testDetachedPanelStopsFollowingTheModel() {
        val panel = ProjectTabsPanel(model)
        panel.attach()
        val before = panel.tabLabels()
        panel.detach()
        model.projectOpened(project)
        assertEquals(before, panel.tabLabels())
    }

    fun testClicksReachTheCallbacks() {
        val panel = ProjectTabsPanel(model)
        val selected = mutableListOf<Project>()
        val closed = mutableListOf<Project>()
        var added: Component? = null
        panel.onSelect = { selected += it }
        panel.onClose = { closed += it }
        panel.onAdd = { added = it }
        val label = panel.tabLabels().single { it.project === project }

        click(label, MouseEvent.BUTTON1)
        click(label, MouseEvent.BUTTON2)
        click(panel.addButton, MouseEvent.BUTTON1)

        assertEquals(listOf(project), selected)
        assertEquals(listOf(project), closed)
        assertSame(panel.addButton, added)
    }

    private fun click(target: Component, button: Int) {
        target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 1, 1, 1, false, button))
    }
}
