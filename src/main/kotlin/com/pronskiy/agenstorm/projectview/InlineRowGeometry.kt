package com.pronskiy.agenstorm.projectview

import java.awt.Rectangle

/**
 * Step O1.2. Where the field goes on the tree, with no Swing call inside it: the caller snapshots the rectangles on
 * the EDT and hands them over, which keeps the arithmetic testable.
 *
 * The field always covers a real row — the element being renamed, or since decision 61 the placeholder node the
 * tree inserted for a new one — so it takes that row's position and height and only has to decide its width.
 */
object InlineRowGeometry {

    /**
     * @param anchor   bounds of the row the field sits on, from `JTree.getPathBounds`.
     * @param viewport the tree's visible rectangle, so the field stops at the right edge instead of running off
     *                 into the horizontal scroll.
     */
    fun place(anchor: Rectangle, viewport: Rectangle, rightGap: Int, minWidth: Int): Rectangle {
        val available = viewport.x + viewport.width - anchor.x - rightGap
        return Rectangle(anchor.x, anchor.y, maxOf(available, minWidth), anchor.height)
    }
}
