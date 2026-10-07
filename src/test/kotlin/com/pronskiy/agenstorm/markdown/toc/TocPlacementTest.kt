package com.pronskiy.agenstorm.markdown.toc

import java.awt.Rectangle
import org.junit.Assert.assertEquals
import org.junit.Test

/** Step W2.8: the widget's own bounds in the editor's layered pane — against the scrollbar, below the inspection widget. */
class TocPlacementTest {

    @Test
    fun againstTheScrollbarBelowTheInspectionWidget() {
        val bounds = TocPlacement.bounds(paneWidth = 1000, scrollbarWidth = 12, statusHeight = 26, width = 220, height = 300, topGap = 4)
        assertEquals(Rectangle(1000 - 12 - 220, 26 + 4, 220, 300), bounds)
    }

    @Test
    fun withoutAnInspectionWidgetItStartsNearTheTop() {
        assertEquals(4, TocPlacement.bounds(paneWidth = 1000, scrollbarWidth = 12, statusHeight = 0, width = 220, height = 300, topGap = 4).y)
    }

    @Test
    fun anEditorNarrowerThanTheCardKeepsItsStartInView() {
        assertEquals(0, TocPlacement.bounds(paneWidth = 150, scrollbarWidth = 12, statusHeight = 0, width = 220, height = 300, topGap = 4).x)
    }
}
