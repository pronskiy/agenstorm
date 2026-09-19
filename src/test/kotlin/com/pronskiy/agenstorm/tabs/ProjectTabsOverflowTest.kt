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

    /** The toolbar decides the width; the strip reads its mode from it (P1.1). */
    private fun layout(panel: ProjectTabsPanel, width: Int) {
        panel.setSize(width, panel.preferredSize.height)
        panel.doLayout()
    }

    /** Step P1.1: the strip is resizable for the toolbar — minimum = every tab as an icon, preferred = every name. */
    fun testMinimumIsTheIconStripAndPreferredIsTheWholeStrip() {
        val panel = panelWith(tabs(12))
        val labels = panel.tabLabels()
        val preferred = panel.preferredSize.width
        val minimum = panel.minimumSize.width
        val chrome = preferred - labels.sumOf { it.preferredWidth(false) }

        assertTrue("the toolbar only shares width with a component whose minimum is below its preferred", minimum < preferred)
        assertEquals(labels.size * JBUI.scale(ProjectTabLabel.COMPACT_WIDTH) + chrome, minimum)
        assertEquals("no cap: twelve names at their natural width", labels.sumOf { it.preferredWidth(false) } + chrome, preferred)

        // Whatever the toolbar grants, preferred and minimum stay content-driven.
        layout(panel, minimum)
        assertEquals(ProjectTabsPanel.Mode.COMPACT, panel.mode)
        assertEquals(preferred, panel.preferredSize.width)
        assertEquals(minimum, panel.minimumSize.width)
    }

    fun testEverythingFitsShowsFullTabs() {
        val panel = panelWith(tabs(3))
        layout(panel, panel.preferredSize.width)

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
        val fullWidth = panel.preferredSize.width

        layout(panel, fullWidth - 1)

        assertEquals(ProjectTabsPanel.Mode.COMPACT, panel.mode)
        assertTrue(panel.tabLabels().all { it.isVisible && it.isCompact })
        assertTrue(panel.tabLabels().all { it.preferredSize.width == JBUI.scale(ProjectTabLabel.COMPACT_WIDTH) })
        assertTrue(panel.minimumSize.width < fullWidth)
        assertFalse(panel.moreButton.isVisible)
        assertTrue(panel.tabLabels().first().toolTipText.startsWith(project.name))
    }

    fun testTooNarrowForAllIconsHidesTheRestBehindTheChevronButKeepsTheOwner() {
        val all = tabs(5)
        val owner = all.last()
        val panel = panelWith(all, owner)
        val compactWidth = panel.minimumSize.width
        layout(panel, compactWidth)
        assertEquals(ProjectTabsPanel.Mode.COMPACT, panel.mode)

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

        // Wide again: everything comes back.
        layout(panel, panel.preferredSize.width)
        assertEquals(ProjectTabsPanel.Mode.FULL, panel.mode)
        assertTrue(panel.hiddenProjects().isEmpty())
    }

    fun testOverflowPopupListsTheHiddenProjects() {
        val hidden = tabs(3).drop(1)
        val group = ProjectTabActions.overflowGroup(hidden, project)
        assertEquals(hidden.map { it.name }, group.getChildren(null).map { it.templatePresentation.text })
    }
}
