package com.pronskiy.agenstorm.markdown.toc

import com.pronskiy.agenstorm.markdown.toc.TocFit.Mode.CARD
import com.pronskiy.agenstorm.markdown.toc.TocFit.Mode.PILL
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Step W1.3: the card when the room past the right margin holds it, the slot's own distance from the editor's edge
 * ([rightInset]: scrollbar, the editor's 20 px and the slot's inner gap) and a gap from the text; the pill otherwise.
 */
class TocFitTest {

    @Test
    fun aWideWindowFitsTheCard() {
        // 120 columns of 5 px = 600 px of text; 850 px of room for a 240 px card, 55 px of slot inset and a 20 px gap.
        assertEquals(CARD, TocFit.mode(viewportWidth = 1450, marginColumns = 120, spaceWidth = 5, cardWidth = 240, rightInset = 55))
    }

    @Test
    fun aNarrowWindowGetsThePill() {
        assertEquals(PILL, TocFit.mode(viewportWidth = 800, marginColumns = 120, spaceWidth = 5, cardWidth = 240, rightInset = 55))
    }

    @Test
    fun theThresholdIsTheCardTheSlotsInsetAndAGap() {
        assertEquals(CARD, TocFit.mode(viewportWidth = 600 + 240 + 55 + 20, marginColumns = 120, spaceWidth = 5, cardWidth = 240, rightInset = 55))
        assertEquals(PILL, TocFit.mode(viewportWidth = 600 + 240 + 55 + 19, marginColumns = 120, spaceWidth = 5, cardWidth = 240, rightInset = 55))
        assertEquals("a scaled gap", PILL, TocFit.mode(viewportWidth = 600 + 240 + 55 + 20, marginColumns = 120, spaceWidth = 5, cardWidth = 240, rightInset = 55, gap = 40))
    }

    @Test
    fun theScrollbarAndTheSlotsGapsCount() {
        // The old rule (card + 2 × 20) would have shown the card here: 280 px of room for a 240 px card.
        assertEquals(PILL, TocFit.mode(viewportWidth = 600 + 280, marginColumns = 120, spaceWidth = 5, cardWidth = 240, rightInset = 15 + 20 + 20))
    }

    @Test
    fun noMarginMeansThePill() {
        assertEquals(PILL, TocFit.mode(viewportWidth = 3000, marginColumns = 0, spaceWidth = 5, cardWidth = 240, rightInset = 55))
        assertEquals(PILL, TocFit.mode(viewportWidth = 3000, marginColumns = 120, spaceWidth = 0, cardWidth = 240, rightInset = 55))
    }
}
