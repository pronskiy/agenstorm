package com.pronskiy.agenstorm.markdown.toc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step W1.2: the depth filter, the two-heading rule and the current section. */
class TocOutlineTest {

    private val entries = listOf(
        TocEntry(1, "A", line = 0, offset = 0),
        TocEntry(2, "B", line = 10, offset = 100),
        TocEntry(3, "C", line = 20, offset = 200),
        TocEntry(4, "D", line = 30, offset = 300),
        TocEntry(2, "E", line = 40, offset = 400),
    )

    @Test
    fun theDepthKeepsThatLevelAndShallower() {
        assertEquals(listOf("A", "B", "C", "E"), TocOutline.visible(entries, 3).map { it.title })
        assertEquals(listOf("A"), TocOutline.visible(entries, 1).map { it.title })
        assertEquals("clamped up to 1", listOf("A"), TocOutline.visible(entries, 0).map { it.title })
        assertEquals("clamped down to 6", entries, TocOutline.visible(entries, 9))
    }

    @Test
    fun onlyDeepHeadingsLeaveNothingToShow() {
        val deep = listOf(TocEntry(4, "x", 0, 0), TocEntry(5, "y", 5, 50))
        val visible = TocOutline.visible(deep, 3)
        assertTrue(visible.isEmpty())
        assertFalse(TocOutline.shows(visible))
    }

    @Test
    fun itShowsFromTwoHeadings() {
        assertFalse(TocOutline.shows(emptyList()))
        assertFalse(TocOutline.shows(entries.take(1)))
        assertTrue(TocOutline.shows(entries.take(2)))
    }

    @Test
    fun theCurrentSectionIsTheLastHeadingAtOrAboveTheTopLine() {
        val visible = TocOutline.visible(entries, 3)
        assertEquals("on the first heading", 0, TocOutline.current(visible, 0))
        assertEquals("between B and C", 1, TocOutline.current(visible, 15))
        assertEquals("exactly on C", 2, TocOutline.current(visible, 20))
        assertEquals("inside D's section, D not listed", 2, TocOutline.current(visible, 35))
        assertEquals("past the last", 3, TocOutline.current(visible, 999))
        assertEquals("nothing listed", -1, TocOutline.current(emptyList(), 5))
        val late = listOf(TocEntry(1, "Late", line = 8, offset = 80), TocEntry(2, "Later", line = 9, offset = 90))
        assertEquals("above the first heading", -1, TocOutline.current(late, 3))
    }
}
