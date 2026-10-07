package com.pronskiy.agenstorm.markdown.toc

/**
 * Step W1.3, pure (decision 97). The card shows when the room between the right margin's column and the editor's edge
 * holds it, the slot's own distance from that edge ([rightInset]) and a [gap] from the text. The slot sits
 * [SLOT_EDGE] px plus the vertical scrollbar's width in from the edge (`EditorImpl`'s layout), and its container, a
 * `FlowLayout(RIGHT, 20, 20)`, adds [SLOT_GAP] more. The margin is what soft wrap at the right margin (Epic V) keeps
 * clear; without a margin there is no telling where the text ends, so the widget stays a pill.
 */
object TocFit {

    enum class Mode { CARD, PILL }

    /** Between the text and the card; unscaled, callers pass `JBUI.scale(GAP)`. */
    const val GAP = 20

    /** `EditorImpl` puts the floating-toolbar slot this far in from the right edge, plus the scrollbar; raw pixels, as there. */
    const val SLOT_EDGE = 20

    /** The slot container's own horizontal gap, used when the panel cannot read it from its `FlowLayout`; raw pixels. */
    const val SLOT_GAP = 20

    fun mode(viewportWidth: Int, marginColumns: Int, spaceWidth: Int, cardWidth: Int, rightInset: Int, gap: Int = GAP): Mode {
        if (marginColumns <= 0 || spaceWidth <= 0) return Mode.PILL
        val room = viewportWidth - marginColumns * spaceWidth
        return if (room >= cardWidth + rightInset + gap) Mode.CARD else Mode.PILL
    }
}
