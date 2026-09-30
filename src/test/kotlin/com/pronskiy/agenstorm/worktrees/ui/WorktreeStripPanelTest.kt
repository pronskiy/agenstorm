package com.pronskiy.agenstorm.worktrees.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.WorktreeSnapshot
import com.pronskiy.agenstorm.worktrees.WorktreeTab
import com.pronskiy.agenstorm.worktrees.WorktreeTabsModel
import java.awt.Font
import java.awt.event.MouseEvent

/** Step T1.5: the strip lays tabs out, keeps the current one, lists the rest under "»" and reports clicks. */
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

    fun testNoTabsHideTheStrip() {
        assertFalse(panel(emptyList(), width = 400).isVisible)
    }

    private fun click(label: javax.swing.JComponent) {
        val event = MouseEvent(label, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1)
        label.mouseListeners.forEach { it.mouseClicked(event) }
    }
}
