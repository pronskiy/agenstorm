package com.pronskiy.agenstorm.tabs.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.hover.HoverListener
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.tabs.ProjectTab
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JPanel

/**
 * One tab: project icon, project name (ellipsized between 72 and 220 px), a × and the base path as tooltip. Like
 * editor tabs, the × is always drawn on the active tab and only on hover on the others, and its slot is reserved
 * either way so the tab never changes width. The active tab is filled; a hovered tab gets the toolbar hover
 * colour. Colours come from named UI keys with the action-button colours as fallback, so every theme paints
 * something sensible. In [isCompact] mode (E2.1 overflow) only the icon is shown, the × goes away and the name
 * moves into the tooltip.
 *
 * An **offloaded** tab (P2.7) is a bookmark for a project Agenstorm closed: a dotted border instead of a fill,
 * the icon at half alpha, the name in the inactive colour, and the path plus "offloaded 2 hours ago" in the
 * tooltip. Its clicks mean *load* and its × means *forget*; what they do is the strip's callbacks' business,
 * the label only reports them. [isLoading] restores the icon while the project is opening.
 *
 * Hover comes from the platform's [HoverListener] (what the stock project widget and the editor tabs use), not
 * from AWT enter/exit events: those are delivered per child, so a pointer leaving the tab through the × — or
 * still over the tab when its window went behind another project's — never told the tab it had left, and the
 * hover fill plus the × stayed on and looked like a second active tab. The hover service watches every mouse
 * event in the IDE and reports one exit for the tab as soon as the pointer is no longer over it.
 */
class ProjectTabLabel(
    val tab: ProjectTab,
    selected: Boolean,
    private val onSelect: () -> Unit,
    private val onClose: () -> Unit,
    private val onContextMenu: (Component, Point) -> Unit = { _, _ -> },
    showIcon: Boolean = AgenstormSettings.getInstance().state.tabsShowIcons,
    private val maxWidth: Int = AgenstormSettings.getInstance().state.tabsMaxWidth,
) : JPanel(BorderLayout(JBUI.scale(4), 0)) {

    /** The open project behind a loaded tab; null for an offloaded one. */
    val project: Project? get() = (tab as? ProjectTab.Loaded)?.project

    val isOffloaded: Boolean get() = tab is ProjectTab.Offloaded

    var isSelected: Boolean = selected
        set(value) {
            if (field == value) return
            field = value
            nameLabel.foreground = textColor()
            updateCloseIcon()
            repaint()
        }

    /** Icon-only rendering for narrow toolbars; see [ProjectTabsPanel]. */
    var isCompact: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            nameLabel.text = if (value) "" else tab.name
            closeLabel.isVisible = !value
            toolTipText = tooltip()
            revalidate()
            repaint()
        }

    /** An offloaded tab whose project is being opened: the icon comes back to full strength meanwhile. */
    var isLoading: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            nameLabel.icon = icon()
            repaint()
        }

    private var hovered = false
    private val showIcon = showIcon
    private val nameLabel = JBLabel(tab.name, if (showIcon) icon() else null, JBLabel.LEFT)

    /** Owns [hovered]; `internal` so tests can drive it — the hover service needs showing windows. */
    internal val hoverListener = object : HoverListener() {
        override fun mouseEntered(component: Component, x: Int, y: Int) = setHovered(true)
        override fun mouseMoved(component: Component, x: Int, y: Int) = Unit
        override fun mouseExited(component: Component) = setHovered(false)
    }

    /** The text shown on the tab (the project name). */
    val title: String get() = nameLabel.text
    internal val closeLabel = JBLabel(HIDDEN_CLOSE).apply {
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { if (isCloseShown) icon = AllIcons.Actions.CloseHovered }
            override fun mouseExited(e: MouseEvent) { if (isCloseShown) icon = AllIcons.Actions.Close }
            override fun mouseClicked(e: MouseEvent) { if (e.button == MouseEvent.BUTTON1 && isCloseShown) onClose() }
        })
    }

    /** Whether the × is drawn right now (its room is reserved regardless). */
    val isCloseShown: Boolean
        get() = closeLabel.icon !== HIDDEN_CLOSE

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0, 8, 0, 6)
        toolTipText = tooltip()
        nameLabel.foreground = textColor()
        nameLabel.iconTextGap = JBUI.scale(6)
        updateCloseIcon()
        add(nameLabel, BorderLayout.CENTER)
        add(closeLabel, BorderLayout.EAST)
        alignmentY = CENTER_ALIGNMENT
        hoverListener.addTo(this)
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) onContextMenu(e.component, e.point)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) onContextMenu(e.component, e.point)
            }
            override fun mouseClicked(e: MouseEvent) {
                when {
                    e.isPopupTrigger -> Unit
                    e.button == MouseEvent.BUTTON1 -> onSelect()
                    e.button == MouseEvent.BUTTON2 -> onClose()
                    e.button == MouseEvent.BUTTON3 -> onContextMenu(e.component, e.point)
                }
            }
        }
        addMouseListener(mouse)
        nameLabel.addMouseListener(mouse)
    }

    /** Lets the strip watch presses and drags on the tab surface (not on the ×) for reordering. */
    fun addDragListener(listener: MouseAdapter) {
        addMouseListener(listener)
        addMouseMotionListener(listener)
        nameLabel.addMouseListener(listener)
        nameLabel.addMouseMotionListener(listener)
    }

    private fun setHovered(value: Boolean) {
        if (hovered == value) return
        hovered = value
        updateCloseIcon()
        repaint()
    }

    /** Active tab: always; other tabs: while hovered. Swapping icons of equal size keeps the layout still. */
    private fun updateCloseIcon() {
        val show = isSelected || hovered
        if (show && !isCloseShown) closeLabel.icon = AllIcons.Actions.Close
        if (!show && isCloseShown) closeLabel.icon = HIDDEN_CLOSE
    }

    /** The name's colour: the toolbar foreground on the active tab, the inactive colour otherwise and on every bookmark. */
    internal fun textColor(): Color = when {
        isOffloaded -> NamedColorUtil.getInactiveTextColor()
        isSelected -> JBColor.namedColor("MainToolbar.Dropdown.foreground", UIUtil.getLabelForeground())
        else -> NamedColorUtil.getInactiveTextColor()
    }

    private fun icon(): Icon? {
        if (!showIcon) return null
        val icon = projectIcon(tab.key, tab.name) ?: return null
        return if (isOffloaded && !isLoading) IconLoader.getTransparentIcon(icon, 0.5f) else icon
    }

    private fun tooltip(): String {
        val path = project?.basePath ?: tab.key
        val offloaded = tab as? ProjectTab.Offloaded
        val detail = when {
            offloaded != null -> AgenstormBundle.message("tabs.offloaded.tooltip", path, DateFormatUtil.formatBetweenDates(offloaded.sinceMs, System.currentTimeMillis()))
            else -> path
        }
        return if (isCompact) "${tab.name} — $detail" else detail
    }

    /** Width the tab wants in the given mode, independent of its current state (the panel plans with both). */
    fun preferredWidth(compact: Boolean): Int {
        if (compact) return JBUI.scale(COMPACT_WIDTH)
        val icon = nameLabel.icon
        val textWidth = getFontMetrics(nameLabel.font).stringWidth(tab.name)
        val iconWidth = if (icon != null) icon.iconWidth + nameLabel.iconTextGap else 0
        val content = insets.left + iconWidth + textWidth + JBUI.scale(4) + AllIcons.Actions.Close.iconWidth + insets.right
        return content.coerceIn(JBUI.scale(MIN_WIDTH), JBUI.scale(maxWidth.coerceIn(MIN_WIDTH, MAX_WIDTH_LIMIT)))
    }

    override fun getPreferredSize(): Dimension = Dimension(preferredWidth(isCompact), JBUI.scale(HEIGHT))

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(if (isCompact) COMPACT_WIDTH else MIN_WIDTH), JBUI.scale(HEIGHT))

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val fill = when {
            isSelected -> SELECTED_BACKGROUND
            hovered -> HOVER_BACKGROUND
            else -> null
        }
        if (fill != null || isOffloaded) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.CurrentTheme.MainToolbar.Dropdown.hoverArc().get()
                val inset = JBUI.scale(2)
                if (fill != null) {
                    g2.color = fill
                    g2.fillRoundRect(0, inset, width, height - 2 * inset, arc, arc)
                }
                if (isOffloaded) {
                    g2.color = NamedColorUtil.getInactiveTextColor()
                    g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(2f, 2f), 0f)
                    g2.drawRoundRect(0, inset, width - 1, height - 2 * inset - 1, arc, arc)
                }
            } finally {
                g2.dispose()
            }
        }
        super.paintComponent(g)
    }

    companion object {
        const val MIN_WIDTH = 72
        /** Default and upper bound of the "max tab width" setting. */
        const val MAX_WIDTH = 220
        const val MAX_WIDTH_LIMIT = 600
        const val COMPACT_WIDTH = 32
        const val HEIGHT = 30
        private val LOG = logger<ProjectTabLabel>()
        /** Same size as the close icon, paints nothing: keeps the ×'s room when it is not shown. */
        private val HIDDEN_CLOSE: Icon = EmptyIcon.create(AllIcons.Actions.Close)
        private val SELECTED_BACKGROUND = JBColor.namedColor("MainToolbar.Dropdown.pressedBackground", JBUI.CurrentTheme.ActionButton.pressedBackground())
        private val HOVER_BACKGROUND = JBColor.namedColor("MainToolbar.Dropdown.hoverBackground", JBUI.CurrentTheme.ActionButton.hoverBackground())

        /** The recent-projects icon for the project at [key] (generated initials when it has none); null if that fails. */
        fun projectIcon(key: String, name: String): Icon? = try {
            RecentProjectsManagerBase.getInstanceEx().getProjectIcon(Path.of(key), true, 16, name)
        } catch (e: Exception) {
            LOG.warn("No project icon for $name", e)
            null
        }
    }
}
