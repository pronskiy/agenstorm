package com.pronskiy.agenstorm.worktrees.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.WorktreeSnapshot
import com.pronskiy.agenstorm.worktrees.WorktreeTab
import com.pronskiy.agenstorm.worktrees.WorktreeTabsModel
import com.pronskiy.agenstorm.worktrees.status.WorktreeStatus
import java.awt.Font
import java.awt.event.MouseEvent

/** Step T1.5: the strip lays tabs out, keeps the current one, lists the rest under "»" and reports clicks; T3.3: badges. */
class WorktreeStripPanelTest : BasePlatformTestCase() {

    private val selected = mutableListOf<WorktreeTab>()

    private fun tabs(current: String): List<WorktreeTab> {
        val worktrees = listOf(Worktree("/r", "main", isMain = true, isLocked = false, lockReason = null, createdAt = 0)) +
            (1..6).map { Worktree("/r/.worktrees/feature-number-$it", "f$it", isMain = false, isLocked = false, lockReason = null, createdAt = it.toLong()) }
        return WorktreeTabsModel.tabs(WorktreeSnapshot("/r/.git", worktrees), current)
    }

    private fun panel(tabs: List<WorktreeTab>, width: Int) = WorktreeStripPanel(onSelect = { selected += it }, onAdd = {}, onContextMenu = { _, _, _ -> }).apply {
        show(tabs)
        setSize(width, preferredSize.height)
        doLayout()
    }

    fun testWideEnoughEveryTabShowsAndThereIsNoOverflow() {
        val strip = panel(tabs("/r"), width = 3000)

        assertTrue(strip.isVisible)
        assertTrue(strip.tabLabels().all { it.isVisible })
        assertEmpty(strip.overflowTabs())
        assertFalse(strip.moreButton().isVisible)
        assertEquals(listOf("main", "feature-number-1", "feature-number-2", "feature-number-3", "feature-number-4", "feature-number-5", "feature-number-6"), strip.tabLabels().map { it.text })
    }

    fun testNarrowTheRestGoesUnderTheOverflowAndTheCurrentTabStays() {
        val tabs = tabs("/r/.worktrees/feature-number-6")
        val strip = panel(tabs, width = 400)

        assertTrue(strip.moreButton().isVisible)
        val shown = strip.tabLabels().filter { it.isVisible }.map { it.text }
        assertTrue(shown.contains("feature-number-6"))
        assertEquals(tabs.map { it.label } - shown.toSet(), strip.overflowTabs().map { it.label })
        assertTrue(strip.tabLabels().single { it.text == "feature-number-6" }.font.isBold)
        assertEquals(Font.PLAIN, strip.tabLabels().first().font.style and Font.BOLD)
    }

    fun testAClickOnAnotherTabSelectsItAndOnTheCurrentOneDoesNothing() {
        val strip = panel(tabs("/r"), width = 3000)
        val (main, first) = strip.tabLabels()

        click(main)
        click(first)

        assertEquals(listOf("/r/.worktrees/feature-number-1"), selected.map { it.path })
    }

    fun testABadgeWidensItsTabKeepsTheNameAsTextAndJoinsTheTooltip() {
        val tabs = tabs("/r")
        val plain = panel(tabs, width = 3000).tabLabels()[1]
        val badge = TabBadge.of(tabs[1].worktree, WorktreeStatus(2, 1, 0, "main"), busyReason = null)
        val strip = panel(tabs, width = 3000).apply { show(tabs, mapOf(tabs[1].path to badge)) }
        val badged = strip.tabLabels()[1]

        assertEquals("feature-number-1", badged.text)
        assertSame(badge, strip.badgeOf(badged))
        assertTrue(badged.preferredSize.width > plain.preferredSize.width)
        assertEquals(plain.preferredSize.width, strip.tabLabels()[2].preferredSize.width)
        assertTrue(badged.toolTipText.contains("2 uncommitted changes"))
        assertTrue(badged.toolTipText.contains("1 commit ahead of main"))
        assertEquals("/r/.worktrees/feature-number-2 \u2014 branch f2", strip.tabLabels()[2].toolTipText)
    }

    fun testTheSameTabsAndBadgesKeepTheLabels() {
        val tabs = tabs("/r")
        val strip = panel(tabs, width = 3000)
        val before = strip.tabLabels()

        strip.show(tabs)

        assertSame(before, strip.tabLabels())
    }

    fun testNoTabsHideTheStrip() {
        assertFalse(panel(emptyList(), width = 400).isVisible)
    }

    private fun click(label: javax.swing.JComponent) {
        val event = MouseEvent(label, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1)
        label.mouseListeners.forEach { it.mouseClicked(event) }
    }
}
