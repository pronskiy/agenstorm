package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import com.pronskiy.agenstorm.tabs.ui.ProjectTabLabel
import com.pronskiy.agenstorm.tabs.ui.ProjectTabsPanel

/** Steps E2.1 / P1.1–P1.3: full tabs while they fit, the widest shrunk first, icon-only after that — never a chevron. */
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

    private fun chromeOf(panel: ProjectTabsPanel): Int = panel.preferredSize.width - panel.tabLabels().sumOf { it.preferredWidth(false) }

    /** Step P1.2: just below full width the widest tabs give first; short names keep their width, nothing goes icon-only. */
    fun testJustBelowFullWidthShrinksTheWidestTabsFirst() {
        val short = FakeProjectHolder.another(project, "a")
        val long = FakeProjectHolder.another(project, "a-very-long-project-name-that-hits-the-cap")
        val panel = panelWith(listOf(project, short, long))
        val full = panel.preferredSize.width
        val width = full - JBUI.scale(20)

        layout(panel, width)

        assertEquals(ProjectTabsPanel.Mode.SHRUNK, panel.mode)
        val labels = panel.tabLabels()
        assertTrue(labels.all { it.isVisible && !it.isCompact })
        val shortLabel = labels.single { it.project === short }
        val longLabel = labels.single { it.project === long }
        assertEquals("a short name keeps its width", shortLabel.preferredWidth(false), shortLabel.width)
        assertTrue("the widest tab is the one cut", longLabel.width < longLabel.preferredWidth(false))
        val used = labels.sumOf { it.width } + chromeOf(panel)
        assertTrue("fills the width it was given (up to integer rounding)", used <= width && used > width - labels.size)
        assertTrue(panel.addButton.x + panel.addButton.width <= width)
        assertEquals("preferred is still the full strip", full, panel.preferredSize.width)
    }

    /** Step P1.2: shrunk tabs end equal, never below MIN_WIDTH; one pixel less than that and the strip goes icon-only. */
    fun testShrunkTabsEndEqualAndStopAtTheMinimumWidth() {
        val names = (1..6).map { "project-with-a-long-name-number-$it" }
        val panel = panelWith(names.map { FakeProjectHolder.another(project, it) })
        val min = JBUI.scale(ProjectTabLabel.MIN_WIDTH)
        val floor = chromeOf(panel) + 6 * min

        layout(panel, floor + JBUI.scale(30))
        assertEquals(ProjectTabsPanel.Mode.SHRUNK, panel.mode)
        val widths = panel.tabLabels().map { it.width }
        assertTrue("equal within a pixel: $widths", widths.max() - widths.min() <= 1)
        assertTrue(widths.all { it >= min })

        layout(panel, floor)
        assertEquals(ProjectTabsPanel.Mode.SHRUNK, panel.mode)
        assertTrue(panel.tabLabels().all { it.width == min })

        layout(panel, floor - 1)
        assertEquals(ProjectTabsPanel.Mode.COMPACT, panel.mode)
        assertTrue(panel.tabLabels().all { it.isCompact })
    }

    fun testEverythingFitsShowsFullTabs() {
        val panel = panelWith(tabs(3))
        layout(panel, panel.preferredSize.width)

        assertEquals(ProjectTabsPanel.Mode.FULL, panel.mode)
        assertTrue(panel.tabLabels().all { it.isVisible && !it.isCompact })
        val labels = panel.tabLabels()
        assertTrue(labels.first().preferredSize.width >= JBUI.scale(ProjectTabLabel.MIN_WIDTH))
        assertTrue("tabs are laid out left to right", labels.zipWithNext().all { (a, b) -> a.x + a.width <= b.x })
        assertTrue(panel.addButton.x > labels.last().x)
    }

    fun testTooNarrowForNamesFallsBackToIconOnlyTabs() {
        val panel = panelWith(tabs(3))
        val fullWidth = panel.preferredSize.width

        layout(panel, chromeOf(panel) + 3 * JBUI.scale(ProjectTabLabel.MIN_WIDTH) - 1)

        assertEquals(ProjectTabsPanel.Mode.COMPACT, panel.mode)
        assertTrue(panel.tabLabels().all { it.isVisible && it.isCompact })
        assertTrue(panel.tabLabels().all { it.preferredSize.width == JBUI.scale(ProjectTabLabel.COMPACT_WIDTH) })
        assertTrue(panel.minimumSize.width < fullWidth)
        assertTrue(panel.tabLabels().first().toolTipText.startsWith(project.name))
    }

    /** Step P1.3: below the icon strip nothing hides and there is no chevron — the toolbar clips, the cap keeps it out of reach. */
    fun testBelowTheIconStripEveryTabStaysAndNothingHidesBehindAChevron() {
        val all = tabs(5)
        val panel = panelWith(all, owner = all.last())
        val compactWidth = panel.minimumSize.width

        layout(panel, compactWidth - 1)

        assertEquals(ProjectTabsPanel.Mode.COMPACT, panel.mode)
        assertTrue("every tab is still there", panel.tabLabels().all { it.isVisible && it.isCompact })
        assertEquals(all.size, panel.tabLabels().count { it.isVisible })
        assertTrue("no chevron component in the strip", panel.components.none { it.javaClass.simpleName.contains("More") })
        assertEquals("the icon strip is the least the strip lays out", compactWidth, panel.addButton.x + panel.addButton.width)

        // Wide again: names come back.
        layout(panel, panel.preferredSize.width)
        assertEquals(ProjectTabsPanel.Mode.FULL, panel.mode)
    }
}
