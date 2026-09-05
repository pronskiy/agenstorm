package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.tabs.ui.ProjectTabLabel
import com.pronskiy.agenstorm.tabs.ui.ProjectTabsPanel

/** Step E2.1: full tabs while they fit, icon-only when they do not, first N plus a chevron after that. */
class ProjectTabsOverflowTest : BasePlatformTestCase() {

    private fun tabs(count: Int): List<Project> = listOf(project) + (1 until count).map { FakeProjectHolder.another(project, "project-$it") }

    private fun panelWith(tabs: List<Project>, owner: Project = project): ProjectTabsPanel {
        val panel = ProjectTabsPanel(ProjectTabsModel.getInstance())
        panel.showTabs(tabs)
        panel.ownerProject = owner
        return panel
    }

    private fun layout(panel: ProjectTabsPanel, available: Int) {
        panel.availableWidthProvider = { available }
        panel.size = panel.preferredSize
        panel.doLayout()
    }

    fun testEverythingFitsShowsFullTabs() {
        val panel = panelWith(tabs(3))
        layout(panel, 100_000)

        assertEquals(ProjectTabsPanel.Mode.FULL, panel.mode)
        assertTrue(panel.tabLabels().all { it.isVisible && !it.isCompact })
        assertFalse(panel.moreButton.isVisible)
        assertTrue(panel.hiddenProjects().isEmpty())
        val labels = panel.tabLabels()
        assertTrue(labels.first().preferredSize.width >= JBUI.scale(ProjectTabLabel.MIN_WIDTH))
        assertTrue("tabs are laid out left to right", labels.zipWithNext().all { (a, b) -> a.x + a.width <= b.x })
        assertTrue(panel.addButton.x > labels.last().x)
    }

    fun testTooNarrowForNamesFallsBackToIconOnlyTabs() {
        val panel = panelWith(tabs(3))
        layout(panel, 100_000)
        val fullWidth = panel.preferredSize.width

        layout(panel, fullWidth - 1)

        assertEquals(ProjectTabsPanel.Mode.COMPACT, panel.mode)
        assertTrue(panel.tabLabels().all { it.isVisible && it.isCompact })
        assertTrue(panel.tabLabels().all { it.preferredSize.width == JBUI.scale(ProjectTabLabel.COMPACT_WIDTH) })
        assertTrue(panel.preferredSize.width < fullWidth)
        assertFalse(panel.moreButton.isVisible)
        assertTrue(panel.tabLabels().first().toolTipText.startsWith(project.name))
    }

    fun testTooNarrowForAllIconsHidesTheRestBehindTheChevronButKeepsTheOwner() {
        val all = tabs(5)
        val owner = all.last()
        val panel = panelWith(all, owner)
        layout(panel, 100_000)
        layout(panel, panel.preferredSize.width - 1)
        assertEquals(ProjectTabsPanel.Mode.COMPACT, panel.mode)
        val compactWidth = panel.preferredSize.width

        layout(panel, compactWidth - 1)

        assertEquals(ProjectTabsPanel.Mode.OVERFLOW, panel.mode)
        assertTrue(panel.moreButton.isVisible)
        val visible = panel.tabLabels().filter { it.isVisible }
        assertTrue("at least one tab stays", visible.isNotEmpty())
        assertTrue("fewer tabs than before", visible.size < all.size)
        assertTrue("the frame's own project is always visible", visible.any { it.project === owner })
        assertEquals(all.size - visible.size, panel.hiddenProjects().size)
        assertFalse(owner in panel.hiddenProjects())
        assertEquals(AgenstormBundle.message("tabs.more.tooltip", all.size - visible.size), panel.moreButton.toolTipText)
        assertTrue(panel.preferredSize.width <= compactWidth - 1)

        // Wide again: everything comes back.
        layout(panel, 100_000)
        assertEquals(ProjectTabsPanel.Mode.FULL, panel.mode)
        assertTrue(panel.hiddenProjects().isEmpty())
    }

    fun testTheCapIsHalfTheWindowAndAbsentWithoutOne() {
        assertEquals(700, ProjectTabsPanel.availableWidthFor(1400))
        assertEquals(Int.MAX_VALUE, ProjectTabsPanel.availableWidthFor(0))
        // A panel outside any window is never capped, so a toolbar being laid out cannot shrink it.
        val panel = panelWith(tabs(6))
        panel.size = panel.preferredSize
        panel.doLayout()
        assertEquals(ProjectTabsPanel.Mode.FULL, panel.mode)
    }

    fun testOverflowPopupListsTheHiddenProjects() {
        val hidden = tabs(3).drop(1)
        val group = ProjectTabActions.overflowGroup(hidden, project)
        assertEquals(hidden.map { it.name }, group.getChildren(null).map { it.templatePresentation.text })
    }
}
