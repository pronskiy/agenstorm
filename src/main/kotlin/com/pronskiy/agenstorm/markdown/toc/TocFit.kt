package com.pronskiy.agenstorm.markdown.toc

/**
 * Step W1.3, pure (decision 97). The card shows when the room between the right margin's column and the editor's edge
 * holds it plus a gap on each side — the floating-toolbar slot's own 20 px from the edge, and as much again from the
 * text. The margin is what soft wrap at the right margin (Epic V) keeps clear; without a margin there is no telling
 * where the text ends, so the widget stays a pill.
 */
object TocFit {

    enum class Mode { CARD, PILL }

    /** Unscaled; callers pass `JBUI.scale(GAP)`. */
    const val GAP = 20

    fun mode(viewportWidth: Int, marginColumns: Int, spaceWidth: Int, cardWidth: Int, gap: Int = GAP): Mode {
        if (marginColumns <= 0 || spaceWidth <= 0) return Mode.PILL
        val room = viewportWidth - marginColumns * spaceWidth
        return if (room >= cardWidth + 2 * gap) Mode.CARD else Mode.PILL
    }
}
