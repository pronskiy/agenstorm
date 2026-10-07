package com.pronskiy.agenstorm.markdown.toc

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
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
        assertEquals("no width, and the scrollbar's own top as its height, so the editor's layout leaves the scrollbar alone", Dimension(0, scrollbar.y), layer.preferredSize)
    }

    /**
     * A main editor keeps its scrollbar and inspection widget in the layered pane, next to our layer. Laid out again
     * and again — every heading edit, fold or sticky-lines change does it — the scrollbar must keep starting below the
     * widget at the same height, whether our layer shows or not.
     */
    fun testTheScrollbarKeepsItsLengthThroughRepeatedLayouts() {
        val file = myFixture.configureByText("a.md", "# Only one heading, so the layer is installed but hidden\n").virtualFile
        val main = EditorFactory.getInstance().createEditor(myFixture.getDocument(myFixture.file), project, file, false, EditorKind.MAIN_EDITOR) as EditorEx
        try {
            val pane = main.scrollPane.parent as JLayeredPane
            assertTrue("the widget's layer is in the pane", pane.components.any { it is TocLayer })
            val scrollbar = main.scrollPane.verticalScrollBar
            assertSame("the scrollbar lives in the layered pane in a main editor", pane, scrollbar.parent)
            val status = (main.scrollPane as JBScrollPane).statusComponent
            assertNotNull("a main editor has its inspection widget", status)
            assertSame("in the same pane", pane, status!!.parent)
            val statusHeight = status.preferredSize.height
            assertTrue(statusHeight > 0)
            pane.setSize(800, 600)
            scrollbar.setBounds(800 - 12, 0, 12, 600)
            repeat(3) { pane.doLayout() }
            assertEquals("below the inspection widget", statusHeight, scrollbar.y)
            assertEquals("and only that much shorter, however often the pane is laid out", 600 - statusHeight, scrollbar.height)
        } finally {
            EditorFactory.getInstance().releaseEditor(main)
        }
    }

    fun testHiddenBelowTwoHeadings() {
        myFixture.configureByText("a.md", "# Only\n")
        TocService.getInstance(project).controllerFor(editor)!!.collectNow()
        assertFalse(layers().single().isVisible)
    }
}
