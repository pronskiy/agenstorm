package com.pronskiy.agenstorm.projectview

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Rectangle

/**
 * Step O1.2: the inline row lands directly below the row it was invoked from (decision 53), indented by where
 * the element will actually live, and stops at the right edge of what is visible.
 */
class InlineRowGeometryTest : BasePlatformTestCase() {

    private val viewport = Rectangle(0, 0, 300, 600)

    private fun place(anchor: Rectangle, placement: Placement, rowHeight: Int = anchor.height) =
        InlineRowGeometry.place(
            anchor, placement, indentPerLevel = 16, viewport = viewport,
            rightGap = 8, minWidth = 100, rowHeight = rowHeight,
        )

    fun testChildRowSitsBelowTheAnchorAndOneLevelIn() {
        val row = place(Rectangle(20, 40, 120, 20), Placement.AS_CHILD)
        assertEquals(36, row.x)
        assertEquals(60, row.y)
        assertEquals(20, row.height)
    }

    fun testSiblingRowSitsBelowTheAnchorAtTheSameLevel() {
        val row = place(Rectangle(20, 40, 120, 20), Placement.AS_SIBLING)
        assertEquals(20, row.x)
        assertEquals(60, row.y)
    }

    fun testRenameTakesTheAnchorsOwnRow() {
        val row = place(Rectangle(20, 40, 120, 20), Placement.OVER_ANCHOR)
        assertEquals(20, row.x)
        assertEquals(40, row.y)
        assertEquals(20, row.height)
    }

    fun testWidthRunsToTheRightEdgeOfTheViewportLessTheGap() {
        val row = place(Rectangle(20, 40, 120, 20), Placement.AS_SIBLING)
        assertEquals(300 - 20 - 8, row.width)
    }

    fun testWidthRespectsTheViewportsOwnOffset() {
        val scrolled = Rectangle(50, 0, 300, 600)
        val row = InlineRowGeometry.place(
            Rectangle(120, 40, 120, 20), Placement.AS_SIBLING,
            indentPerLevel = 16, viewport = scrolled, rightGap = 8, minWidth = 100, rowHeight = 20,
        )
        assertEquals(50 + 300 - 120 - 8, row.width)
    }

    fun testDeeplyIndentedRowStillGetsAUsableWidth() {
        val row = place(Rectangle(280, 40, 20, 20), Placement.AS_CHILD)
        assertEquals(100, row.width)
    }

    fun testHeightIsOneNaturalRow() {
        // IntelliJ trees are variable-height, so JTree.getRowHeight() is 0 and only the rectangle is truthful.
        assertEquals(27, place(Rectangle(0, 0, 100, 27), Placement.AS_CHILD).height)
    }

    fun testANewRowSitsInTheGapTheSpacerOpened() {
        // The spacer has doubled the anchor to 40; the field takes its lower half and covers nothing.
        val row = place(Rectangle(20, 40, 120, 40), Placement.AS_CHILD, rowHeight = 20)

        assertEquals(60, row.y)
        assertEquals(20, row.height)
        assertEquals(36, row.x)
    }

    fun testRenameStillTakesTheWholeAnchorRow() {
        val row = place(Rectangle(20, 40, 120, 20), Placement.OVER_ANCHOR, rowHeight = 20)

        assertEquals(40, row.y)
        assertEquals(20, row.height)
    }

    fun testIndentIsMeasuredFromTheFirstUsableSample() {
        assertEquals(18, InlineRowGeometry.indentPerLevel(listOf(0, 18, 24), fallback = 16))
    }

    fun testIndentFallsBackWhenNothingCanBeMeasured() {
        assertEquals(16, InlineRowGeometry.indentPerLevel(emptyList(), fallback = 16))
        assertEquals(16, InlineRowGeometry.indentPerLevel(listOf(0, 0), fallback = 16))
    }
}
