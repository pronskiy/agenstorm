package com.pronskiy.agenstorm.projectview

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Rectangle

/** Step O1.2: the field takes its row's place and runs to the right edge of what is visible. */
class InlineRowGeometryTest : BasePlatformTestCase() {

    private val viewport = Rectangle(0, 0, 300, 600)

    private fun place(anchor: Rectangle, viewport: Rectangle = this.viewport) =
        InlineRowGeometry.place(anchor, viewport, rightGap = 8, minWidth = 100)

    fun testTheFieldTakesItsRowsPositionAndHeight() {
        val row = place(Rectangle(20, 40, 120, 22))

        assertEquals(20, row.x)
        assertEquals(40, row.y)
        assertEquals(22, row.height)
    }

    fun testWidthRunsToTheRightEdgeOfTheViewportLessTheGap() {
        assertEquals(300 - 20 - 8, place(Rectangle(20, 40, 120, 20)).width)
    }

    fun testWidthRespectsTheViewportsOwnOffset() {
        val row = place(Rectangle(120, 40, 120, 20), viewport = Rectangle(50, 0, 300, 600))

        assertEquals(50 + 300 - 120 - 8, row.width)
    }

    fun testADeeplyIndentedRowStillGetsAUsableWidth() {
        assertEquals(100, place(Rectangle(280, 40, 20, 20)).width)
    }
}
