package com.pronskiy.agenstorm.markdown.toc

/**
 * Step W1.3, pure (decisions 97, 99). The card fits when the room between the right margin's column and the editor's
 * edge holds it, the widget's distance from that edge ([rightInset], the vertical scrollbar since decision 98) and a
 * [gap] from the text. The margin is what soft wrap at the right margin (Epic V) keeps clear; without a margin there
 * is no telling where the text ends, so the widget stays folded. The rule only decides while the user has made no
 * fold choice of their own ([folded]).
 */
object TocFit {

    enum class Mode { CARD, PILL }

    /** Between the text and the card; unscaled, callers pass `JBUI.scale(GAP)`. */
    const val GAP = 20

    /** `markdownTocFold` values: the width rule decides, or the user's click does. */
    const val FOLD_AUTO = "auto"
    const val FOLD_FOLDED = "folded"
    const val FOLD_UNFOLDED = "unfolded"

    fun mode(viewportWidth: Int, marginColumns: Int, spaceWidth: Int, cardWidth: Int, rightInset: Int, gap: Int = GAP): Mode {
        if (marginColumns <= 0 || spaceWidth <= 0) return Mode.PILL
        val room = viewportWidth - marginColumns * spaceWidth
        return if (room >= cardWidth + rightInset + gap) Mode.CARD else Mode.PILL
    }

    /** Whether the card is folded into its icon: the user's [fold] choice, or the width rule's [mode] while it is `auto`. */
    fun folded(fold: String, mode: Mode): Boolean = when (fold) {
        FOLD_FOLDED -> true
        FOLD_UNFOLDED -> false
        else -> mode == Mode.PILL
    }
}
