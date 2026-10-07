package com.pronskiy.agenstorm.markdown.toc

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI

/** Step W2.3: the panel picks card or pill from the viewport and keeps the card within its width and height caps. */
class TocPanelTest : BasePlatformTestCase() {

    private fun state(vararg titles: String) =
        TocState(titles.mapIndexed { i, t -> TocEntry(1 + i % 3, t, line = i * 10, offset = i * 100) }, current = 0, shows = true)

    private val wide = TocController.Viewport(width = 3000, height = 800, marginColumns = 80, spaceWidth = 7, scrollbarWidth = 15)
    private val narrow = TocController.Viewport(width = 700, height = 800, marginColumns = 80, spaceWidth = 7, scrollbarWidth = 15)

    fun testCardWhenItFitsBesideTheMargin() {
        val panel = TocPanel()
        panel.show(state("Agenstorm", "Install", "Features"), wide)
        assertEquals(TocFit.Mode.CARD, panel.mode)
        assertTrue(panel.preferredSize.width >= JBUI.scale(TocPanel.MIN_WIDTH))
    }

    fun testPillWhenNarrowAndBackToTheCardWhenWide() {
        val panel = TocPanel()
        panel.show(state("Agenstorm", "Install", "Features"), narrow)
        assertEquals(TocFit.Mode.PILL, panel.mode)
        assertTrue("the pill is small", panel.preferredSize.width < JBUI.scale(TocPanel.MIN_WIDTH))
        panel.show(state("Agenstorm", "Install", "Features"), wide)
        assertEquals(TocFit.Mode.CARD, panel.mode)
    }

    fun testTheScrollbarAndTheSlotsOffsetLeaveRoomForTheText() {
        val panel = TocPanel()
        val state = state("Agenstorm", "Install", "Features")
        panel.show(state, wide)
        val card = panel.cardWidth()
        // Room for the card and two 20 px gaps — enough under the old rule — but not for the scrollbar and the slot's inner gap too.
        panel.show(state, TocController.Viewport(width = 80 * 7 + card + 40, height = 800, marginColumns = 80, spaceWidth = 7, scrollbarWidth = 15))
        assertEquals(TocFit.Mode.PILL, panel.mode)
        panel.show(state, TocController.Viewport(width = 80 * 7 + card + 15 + 20 + 20 + JBUI.scale(TocFit.GAP), height = 800, marginColumns = 80, spaceWidth = 7, scrollbarWidth = 15))
        assertEquals(TocFit.Mode.CARD, panel.mode)
    }

    fun testLongTitlesAreCappedAtTheMaximumWidth() {
        val panel = TocPanel()
        panel.show(state("x".repeat(400), "Install"), wide)
        assertEquals("capped, not grown to the title", JBUI.scale(TocPanel.MAX_WIDTH), panel.cardWidth())
    }

    fun testManyHeadingsAreCappedAtHalfTheEditor() {
        val panel = TocPanel()
        panel.show(state(*Array(200) { "Heading $it" }), wide)
        val insets = panel.insets
        assertEquals("capped at half the editor", wide.height / 2, panel.preferredSize.height - insets.top - insets.bottom)
    }
}
