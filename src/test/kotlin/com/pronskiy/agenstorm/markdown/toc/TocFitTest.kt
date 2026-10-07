package com.pronskiy.agenstorm.markdown.toc

import com.pronskiy.agenstorm.markdown.toc.TocFit.Mode.CARD
import com.pronskiy.agenstorm.markdown.toc.TocFit.Mode.PILL
import org.junit.Assert.assertEquals
import org.junit.Test

/** Step W1.3: the card when the room past the right margin holds it and a gap on each side, the pill otherwise. */
class TocFitTest {

    @Test
    fun aWideWindowFitsTheCard() {
        // 120 columns of 5 px = 600 px of text; 850 px of room for a 240 px card and two 20 px gaps.
        assertEquals(CARD, TocFit.mode(viewportWidth = 1450, marginColumns = 120, spaceWidth = 5, cardWidth = 240))
    }

    @Test
    fun aNarrowWindowGetsThePill() {
        assertEquals(PILL, TocFit.mode(viewportWidth = 800, marginColumns = 120, spaceWidth = 5, cardWidth = 240))
    }

    @Test
    fun theThresholdIsTheCardPlusTwoGaps() {
        assertEquals(CARD, TocFit.mode(viewportWidth = 600 + 280, marginColumns = 120, spaceWidth = 5, cardWidth = 240))
        assertEquals(PILL, TocFit.mode(viewportWidth = 600 + 279, marginColumns = 120, spaceWidth = 5, cardWidth = 240))
        assertEquals("a scaled gap", PILL, TocFit.mode(viewportWidth = 600 + 280, marginColumns = 120, spaceWidth = 5, cardWidth = 240, gap = 40))
    }

    @Test
    fun noMarginMeansThePill() {
        assertEquals(PILL, TocFit.mode(viewportWidth = 3000, marginColumns = 0, spaceWidth = 5, cardWidth = 240))
        assertEquals(PILL, TocFit.mode(viewportWidth = 3000, marginColumns = 120, spaceWidth = 0, cardWidth = 240))
    }
}
