package com.pronskiy.agenstorm.projectview

import java.awt.Rectangle

/**
 * Where the inline row goes, relative to the row it was invoked from.
 *
 * Both sit *below* the anchor — the difference is only the indent, which says where the element will actually
 * land: [AS_CHILD] when the anchor is the folder the element goes into, [AS_SIBLING] when the anchor is a file
 * and the new element joins it in the same folder.
 */
enum class Placement {
    AS_CHILD,
    AS_SIBLING,
    /** Renaming: the field takes the anchor's own row rather than a new one below it. */
    OVER_ANCHOR,
}

/**
 * Step O1.2. The arithmetic of putting the inline row on the tree, with no Swing call inside it: the caller
 * snapshots the rectangles on the EDT and hands them over, which is what makes the placement rule testable.
 *
 * The rule is Roman's (decision 53): the field appears directly below the row that was clicked, and only when
 * there was no click does the caller anchor it to the target folder's last visible child instead.
 */
object InlineRowGeometry {

    /**
     * @param anchor    bounds of the row the field is placed against, from `JTree.getPathBounds`.
     * @param rowHeight one natural row, measured before the spacer doubled the anchor — never
     *                  `JTree.getRowHeight()`, which is 0 in the variable-height mode IntelliJ trees run in.
     * @param viewport  the tree's visible rectangle, so the field stops at the right edge instead of running
     *                  off into the horizontal scroll.
     */
    fun place(
        anchor: Rectangle,
        placement: Placement,
        indentPerLevel: Int,
        viewport: Rectangle,
        rightGap: Int,
        minWidth: Int,
        rowHeight: Int,
    ): Rectangle {
        val x = when (placement) {
            Placement.AS_CHILD -> anchor.x + indentPerLevel
            Placement.AS_SIBLING, Placement.OVER_ANCHOR -> anchor.x
        }
        // A new element sits in the second half of the anchor's row, which the spacer renderer has just made
        // twice as tall — so the rows below have already moved down and nothing is covered. Without the
        // spacer the same arithmetic lands it immediately below the anchor, which is where it used to sit.
        val y = if (placement == Placement.OVER_ANCHOR) anchor.y else anchor.y + rowHeight
        val height = if (placement == Placement.OVER_ANCHOR) anchor.height else rowHeight
        val available = viewport.x + viewport.width - x - rightGap
        return Rectangle(x, y, maxOf(available, minWidth), height)
    }

    /**
     * The indent of one tree level, measured from a parent row and one of its children rather than assumed:
     * `DefaultTreeUI` indents through its own `Control.Painter`, so the `BasicTreeUI` defaults in `UIManager`
     * do not describe what is painted. [fallback] covers a tree with no expanded parent to measure from.
     */
    fun indentPerLevel(samples: List<Int>, fallback: Int): Int =
        samples.firstOrNull { it > 0 } ?: fallback
}
