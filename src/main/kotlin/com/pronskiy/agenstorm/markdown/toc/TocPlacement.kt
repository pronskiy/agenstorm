package com.pronskiy.agenstorm.markdown.toc

import java.awt.Rectangle

/**
 * Step W2.8, pure (decision 98). Where the widget sits in the editor's layered pane: its right edge against the
 * vertical scrollbar, its top [topGap] below the inspection widget (or the pane's top when there is none), and never
 * starting left of the pane, so in an editor narrower than the card its start stays in view.
 */
object TocPlacement {

    fun bounds(paneWidth: Int, scrollbarWidth: Int, statusHeight: Int, width: Int, height: Int, topGap: Int): Rectangle =
        Rectangle((paneWidth - scrollbarWidth - width).coerceAtLeast(0), statusHeight + topGap, width, height)
}
