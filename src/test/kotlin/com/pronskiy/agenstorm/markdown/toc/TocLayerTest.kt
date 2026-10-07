package com.pronskiy.agenstorm.markdown.toc

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import java.awt.Dimension
import javax.swing.JLayeredPane

/** Step W2.8: the widget joins a Markdown editor's layered pane on open, places itself, and leaves with its controller. */
class TocLayerTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private val editor get() = myFixture.editor as EditorEx
    private fun pane(): JLayeredPane = editor.scrollPane.parent as JLayeredPane
    private fun layers() = pane().components.filterIsInstance<TocLayer>()

    fun testAMarkdownEditorGetsTheWidgetWhenOpened() {
        myFixture.configureByText("a.md", "# A\n\n## B\n")
        assertNotNull(TocService.getInstance(project).controllerFor(editor))
        assertEquals(1, layers().size)
    }

    fun testOtherEditorsDoNot() {
        myFixture.configureByText("a.php", "<?php\n")
        assertNull(TocService.getInstance(project).controllerFor(editor))
        assertTrue(layers().isEmpty())
    }

    fun testTheWidgetIsReplacedNotDoubledAndLeavesWithItsController() {
        myFixture.configureByText("a.md", "# A\n\n## B\n")
        val lifetime = Disposer.newDisposable(testRootDisposable, "editor")
        TocService.getInstance(project).attach(editor, lifetime)
        assertEquals(1, layers().size)
        Disposer.dispose(lifetime)
        assertTrue(layers().isEmpty())
    }

    fun testItPlacesItselfAgainstTheScrollbarAndLeavesTheScrollbarAlone() {
        myFixture.configureByText("a.md", "# A\n\n## B\n\n### C\n")
        TocService.getInstance(project).controllerFor(editor)!!.collectNow()
        val pane = pane()
        pane.setSize(1000, 600)
        pane.doLayout()
        val layer = layers().single()
        assertTrue(layer.isVisible)
        val scrollbar = editor.scrollPane.verticalScrollBar
        val scrollbarWidth = if (scrollbar.isVisible) scrollbar.width else 0
        assertEquals("against the scrollbar", 1000 - scrollbarWidth, layer.x + layer.width)
        assertTrue("a size of its own", layer.width > 0 && layer.height > 0)
        assertEquals("no preferred size for the editor's layout, so the scrollbar is not pushed down", Dimension(0, 0), layer.preferredSize)
    }

    fun testHiddenBelowTwoHeadings() {
        myFixture.configureByText("a.md", "# Only\n")
        TocService.getInstance(project).controllerFor(editor)!!.collectNow()
        assertFalse(layers().single().isVisible)
    }
}
