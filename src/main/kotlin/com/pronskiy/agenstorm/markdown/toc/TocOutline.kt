package com.pronskiy.agenstorm.markdown.toc

/** Step W1.2, pure. Which headings the contents widget lists, whether it shows at all, and which one is current. */
object TocOutline {

    /** The depths the settings page allows (decision 97); anything outside is clamped. */
    val DEPTHS = 1..6

    /** The entries at [depth] or shallower, in document order. */
    fun visible(entries: List<TocEntry>, depth: Int): List<TocEntry> {
        val max = depth.coerceIn(DEPTHS)
        return entries.filter { it.level <= max }
    }

    /** One heading is no contents list: the widget shows from two up. */
    fun shows(visible: List<TocEntry>): Boolean = visible.size >= 2

    /** The index in [visible] of the last entry starting at or above [topLine]; -1 while the view is above the first. */
    fun current(visible: List<TocEntry>, topLine: Int): Int = visible.indexOfLast { it.line <= topLine }
}
