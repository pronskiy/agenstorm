package com.pronskiy.agenstorm.softwrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step V1.1: the bounded width, and the IDE's soft-wrap list. */
class MarginWrapTest {

    @Test
    fun theWindowWinsWhenNarrowerTheMarginWhenWider() {
        assertEquals("120 columns of 5 px", 600, MarginWrap.width(visible = 1450, rightMarginColumns = 120, spaceWidth = 5))
        assertEquals("a window narrower than the margin", 400, MarginWrap.width(visible = 400, rightMarginColumns = 120, spaceWidth = 5))
        assertEquals("no margin", 1450, MarginWrap.width(visible = 1450, rightMarginColumns = 0, spaceWidth = 5))
        assertEquals("no width to go by", 1450, MarginWrap.width(visible = 1450, rightMarginColumns = 120, spaceWidth = 0))
    }

    @Test
    fun theSoftWrapListIsMatchedLikeFileMasks() {
        val defaults = "*.md; *.txt; *.rst; *.adoc"
        assertTrue(MarginWrap.matches(defaults, "README.md"))
        assertTrue(MarginWrap.matches(defaults, "NOTES.MD"))
        assertTrue(MarginWrap.matches(defaults, "a.txt"))
        assertFalse(MarginWrap.matches(defaults, "Login.php"))
        assertFalse("a mask matches the whole name", MarginWrap.matches(defaults, "page.mdx"))
        assertTrue(MarginWrap.matches("CHANGELOG?.md,notes*", "CHANGELOG2.md"))
        assertTrue(MarginWrap.matches("CHANGELOG?.md,notes*", "notes-2026"))
        assertFalse(MarginWrap.matches("", "README.md"))
        assertFalse("dots are literal", MarginWrap.matches("*.md", "READMEXmd"))
    }
}
