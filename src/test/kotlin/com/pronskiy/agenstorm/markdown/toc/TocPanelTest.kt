package com.pronskiy.agenstorm.markdown.toc

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import java.awt.Container
import javax.swing.SwingUtilities

/**
 * Steps W2.3, W2.9 and W2.10: the panel picks card or folded icon from the viewport unless the user chose, keeps the
 * card within its width and height caps, and keeps the fold icon in its top-right corner.
 */
class TocPanelTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private fun state(vararg titles: String) =
        TocState(titles.mapIndexed { i, t -> TocEntry(1 + i % 3, t, line = i * 10, offset = i * 100) }, current = 0, shows = true)

    private val wide = TocController.Viewport(width = 3000, height = 800, marginColumns = 80, spaceWidth = 7, scrollbarWidth = 15)
    private val narrow = TocController.Viewport(width = 700, height = 800, marginColumns = 80, spaceWidth = 7, scrollbarWidth = 15)

    fun testCardWhenItFitsBesideTheMargin() {
        val panel = TocPanel(null)
        panel.show(state("Agenstorm", "Install", "Features"), wide)
        assertEquals(TocFit.Mode.CARD, panel.mode)
        assertFalse(panel.folded)
        assertTrue(panel.preferredSize.width >= JBUI.scale(TocPanel.MIN_WIDTH))
    }

    fun testFoldedWhenNarrowAndBackToTheCardWhenWide() {
        val panel = TocPanel(null)
        panel.show(state("Agenstorm", "Install", "Features"), narrow)
        assertTrue(panel.folded)
        assertTrue("folded is just the icon", panel.preferredSize.width < JBUI.scale(TocPanel.MIN_WIDTH))
        panel.show(state("Agenstorm", "Install", "Features"), wide)
        assertFalse(panel.folded)
    }

    fun testTheUsersChoiceWinsOverTheWidth() {
        val panel = TocPanel(null)
        AgenstormSettings.getInstance().state.markdownTocFold = TocFit.FOLD_FOLDED
        panel.show(state("Agenstorm", "Install", "Features"), wide)
        assertTrue("folded on a wide window stays folded", panel.folded)

        AgenstormSettings.getInstance().state.markdownTocFold = TocFit.FOLD_UNFOLDED
        panel.show(state("Agenstorm", "Install", "Features"), narrow)
        assertFalse("unfolded on a narrow window stays unfolded", panel.folded)
        assertTrue(panel.preferredSize.width >= JBUI.scale(TocPanel.MIN_WIDTH))
    }

    fun testTheIconTogglesTheChoiceForEveryEditor() {
        var fired = 0
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { fired++ })
        val panel = TocPanel(null)
        panel.show(state("Agenstorm", "Install", "Features"), wide)
        panel.toggleFold()
        assertEquals(TocFit.FOLD_FOLDED, AgenstormSettings.getInstance().state.markdownTocFold)
        assertTrue(panel.folded)
        panel.toggleFold()
        assertEquals(TocFit.FOLD_UNFOLDED, AgenstormSettings.getInstance().state.markdownTocFold)
        assertFalse(panel.folded)
        assertEquals("every editor follows through the settings topic", 2, fired)
    }

    fun testTheIconSitsInTheTopRightCornerUnfoldedAndFolded() {
        val panel = TocPanel(null)
        panel.show(state("Agenstorm", "Install", "Features"), wide)
        assertIconInTopRightCorner(panel)
        panel.toggleFold()
        assertIconInTopRightCorner(panel)
    }

    fun testTheScrollbarLeavesRoomForTheText() {
        val panel = TocPanel(null)
        val state = state("Agenstorm", "Install", "Features")
        panel.show(state, wide)
        val card = panel.cardWidth()
        val fits = 80 * 7 + card + 15 + JBUI.scale(TocFit.GAP)
        panel.show(state, TocController.Viewport(width = fits - 1, height = 800, marginColumns = 80, spaceWidth = 7, scrollbarWidth = 15))
        assertEquals(TocFit.Mode.PILL, panel.mode)
        panel.show(state, TocController.Viewport(width = fits, height = 800, marginColumns = 80, spaceWidth = 7, scrollbarWidth = 15))
        assertEquals(TocFit.Mode.CARD, panel.mode)
    }

    fun testLongTitlesAreCappedAtTheMaximumWidth() {
        val panel = TocPanel(null)
        panel.show(state("x".repeat(400), "Install"), wide)
        assertEquals("capped, not grown to the title", JBUI.scale(TocPanel.MAX_WIDTH), panel.cardWidth())
        assertEquals("narrower than the first cut", 220, TocPanel.MAX_WIDTH)
    }

    fun testManyHeadingsAreCappedAtHalfTheEditor() {
        val panel = TocPanel(null)
        panel.show(state(*Array(200) { "Heading $it" }), wide)
        val insets = panel.insets
        assertEquals("capped at half the editor", wide.height / 2, panel.preferredSize.height - insets.top - insets.bottom)
    }

    private fun assertIconInTopRightCorner(panel: TocPanel) {
        panel.setSize(panel.preferredSize)
        layOut(panel)
        val icon = panel.foldIcon
        assertTrue("the icon is in the panel", SwingUtilities.isDescendingFrom(icon, panel))
        val at = SwingUtilities.convertPoint(icon.parent, icon.location, panel)
        assertEquals("right edge", panel.width - panel.insets.right, at.x + icon.width)
        assertEquals("top edge", panel.insets.top, at.y)
    }

    private fun layOut(container: Container) {
        container.doLayout()
        for (child in container.components) if (child is Container) layOut(child)
    }
}
