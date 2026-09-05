package com.pronskiy.agenstorm.tabs.ui

import java.awt.CardLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The toolbar slot: the stock project widget on one card, the tab strip on the other. Sizes follow the card
 * that is showing (a plain `CardLayout` would reserve room for the wider of the two).
 */
class SwitchingPanel(val stock: JComponent, val tabs: ProjectTabsPanel) : JPanel(CardLayout()) {

    var isShowingTabs: Boolean = false
        private set

    init {
        isOpaque = false
        add(stock, STOCK)
        add(tabs, TABS)
        showTabs(false)
    }

    fun showTabs(show: Boolean) {
        if (show == isShowingTabs && (if (show) tabs.isVisible else stock.isVisible)) return
        isShowingTabs = show
        (layout as CardLayout).show(this, if (show) TABS else STOCK)
        revalidate()
        repaint()
    }

    private val visibleCard: JComponent
        get() = if (isShowingTabs) tabs else stock

    override fun getPreferredSize(): Dimension = if (isPreferredSizeSet) super.getPreferredSize() else visibleCard.preferredSize

    override fun getMinimumSize(): Dimension = if (isMinimumSizeSet) super.getMinimumSize() else visibleCard.minimumSize

    override fun getMaximumSize(): Dimension = if (isMaximumSizeSet) super.getMaximumSize() else visibleCard.maximumSize

    private companion object {
        const val STOCK = "stock"
        const val TABS = "tabs"
    }
}
