package com.pronskiy.agenstorm.tabs.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.ui.hover.HoverListener
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.ProjectNameProvider
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
 * An **offloaded** tab (P2.7) is a bookmark for a project Agenstorm closed: a faint dotted border instead of a
 * fill, the icon at half alpha, the name in the inactive colour, and the path plus "offloaded 2 hours ago" in the
 * tooltip. Its clicks mean *load* and its × means *forget*; what they do is the strip's callbacks' business,
 * the label only reports them. [isLoading] restores the icon while the project is opening.
 *
 * Hover comes from the platform's [HoverListener] (what the stock project widget and the editor tabs use), not
 * from AWT enter/exit events: those are delivered per child, so a pointer leaving the tab through the × — or
 * still over the tab when its window went behind another project's — never told the tab it had left, and the
 * hover fill plus the × stayed on and looked like a second active tab. The hover service watches every mouse
 * event in the IDE and reports one exit for the tab as soon as the pointer is no longer over it.
 *
 * The right click goes through a [PopupHandler] on the tab, its name and its ×. The main toolbar adds its own
 * "Customize Toolbar…" handler to every component in its tree that has no `PopupHandler` yet, so a plain mouse
 * listener here opened both menus at once.
 */
class ProjectTabLabel(
    val tab: ProjectTab,
    selected: Boolean,
    private val onSelect: () -> Unit,
    private val onClose: () -> Unit,
    private val onContextMenu: (Component, Point) -> Unit = { _, _ -> },
    showIcon: Boolean = AgenstormSettings.getInstance().state.tabsShowIcons,
    private val maxWidth: Int = AgenstormSettings.getInstance().state.tabsMaxWidth,
    iconOf: (key: String, name: String) -> Icon? = { key, name -> projectIcon(key, name) },
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
            nameLabel.text = if (value) "" else tab.label
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

    /**
     * Step X6.6. The sessions waiting for input in this tab's project, from `core/ProjectAttention` (decision 107): a
     * dot on the icon and their names in the tooltip while there are any. An offloaded tab never has one.
     */
    var waiting: List<String> = emptyList()
        set(value) {
            val names = if (isOffloaded) emptyList() else value
            if (field == names) return
            field = names
            nameLabel.icon = icon()
            toolTipText = tooltip()
            revalidate()
            repaint()
        }

    internal val hasBadge: Boolean get() = waiting.isNotEmpty()

    private var hovered = false
    private val showIcon = showIcon

    /** The recent-projects icon, looked up once — by the raw name, whose initials a generated icon shows, not the label. */
    private val plainIcon: Icon? = if (showIcon) iconOf(tab.key, tab.name) else null
    private val nameLabel = JBLabel(tab.label, icon(), JBLabel.LEFT)

    /** Owns [hovered]; `internal` so tests can drive it — the hover service needs showing windows. */
    internal val hoverListener = object : HoverListener() {
        override fun mouseEntered(component: Component, x: Int, y: Int) = setHovered(true)
        override fun mouseMoved(component: Component, x: Int, y: Int) = Unit
        override fun mouseExited(component: Component) = setHovered(false)
    }

    /** The text shown on the tab ([ProjectTab.label]). */
    val title: String get() = nameLabel.text

    /** The icon in front of the name: the project's, with the waiting badge when there is one. */
    internal val nameIcon: Icon? get() = nameLabel.icon
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
            override fun mouseClicked(e: MouseEvent) {
                when {
                    e.isPopupTrigger -> Unit
                    e.button == MouseEvent.BUTTON1 -> onSelect()
                    e.button == MouseEvent.BUTTON2 -> onClose()
                }
            }
        }
        addMouseListener(mouse)
        nameLabel.addMouseListener(mouse)
        val popup = object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) = onContextMenu(comp, Point(x, y))
        }
        for (component in listOf(this, nameLabel, closeLabel)) component.addMouseListener(popup)
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

    /** The bookmark's dotted border: the inactive text colour, faded so the mark reads without shouting (Roman, 2026-09-19). */
    internal fun borderColor(): Color = ColorUtil.withAlpha(NamedColorUtil.getInactiveTextColor(), BORDER_ALPHA)

    private fun icon(): Icon? {
        val icon = plainIcon?.let { if (isOffloaded && !isLoading) IconLoader.getTransparentIcon(it, 0.5f) else it }
        if (waiting.isEmpty()) return icon
        return if (icon != null) Badged(icon) else LONE_BADGE
    }

    /**
     * Step X6.6. The project icon with an orange dot on its top-right corner, ringed with what is behind the tab — its
     * fill when it has one, the toolbar otherwise — so the dot reads on any icon. Same size as the icon: the tab keeps
     * its width.
     */
    private inner class Badged(private val icon: Icon) : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            icon.paintIcon(c, g, x, y)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val ring = JBUI.scale(BADGE_RING)
                val dot = JBUI.scale(BADGE_DOT)
                val outer = dot + 2 * ring
                val left = x + iconWidth - outer + ring
                val top = y - ring
                g2.color = behind()
                g2.fillOval(left, top, outer, outer)
                fill()?.let { g2.color = it; g2.fillOval(left, top, outer, outer) }
                g2.color = BADGE_COLOR
                g2.fillOval(left + ring, top + ring, dot, dot)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth() = icon.iconWidth
        override fun getIconHeight() = icon.iconHeight
    }

    /** The tab's own fill under the badge's ring, as [paintComponent] draws it. */
    private fun fill(): Color? = when {
        isSelected -> SELECTED_BACKGROUND
        hovered -> HOVER_BACKGROUND
        else -> null
    }

    /** What the tab is drawn on: the nearest opaque component behind it. */
    private fun behind(): Color = generateSequence(parent) { it.parent }.firstOrNull { it.isOpaque }?.background ?: UIUtil.getPanelBackground()

    private fun tooltip(): String {
        val path = project?.basePath ?: tab.key
        val offloaded = tab as? ProjectTab.Offloaded
        val detail = when {
            offloaded != null -> AgenstormBundle.message("tabs.offloaded.tooltip", path, DateFormatUtil.formatBetweenDates(offloaded.sinceMs, System.currentTimeMillis()))
            else -> path
        }
        val text = if (isCompact) "${tab.label} — $detail" else detail
        if (waiting.isEmpty()) return text
        return HtmlBuilder().append(text).br().append(AgenstormBundle.message("tabs.waiting.tooltip", waiting.joinToString(", "))).wrapWithHtmlBody().toString()
    }

    /** Width the tab wants in the given mode, independent of its current state (the panel plans with both). */
    fun preferredWidth(compact: Boolean): Int {
        if (compact) return JBUI.scale(COMPACT_WIDTH)
        val icon = nameLabel.icon
        val textWidth = getFontMetrics(nameLabel.font).stringWidth(tab.label)
        val iconWidth = if (icon != null) icon.iconWidth + nameLabel.iconTextGap else 0
        val content = insets.left + iconWidth + textWidth + JBUI.scale(4) + AllIcons.Actions.Close.iconWidth + insets.right
        return content.coerceIn(JBUI.scale(MIN_WIDTH), JBUI.scale(maxWidth.coerceIn(MIN_WIDTH, MAX_WIDTH_LIMIT)))
    }

    override fun getPreferredSize(): Dimension = Dimension(preferredWidth(isCompact), JBUI.scale(HEIGHT))

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(if (isCompact) COMPACT_WIDTH else MIN_WIDTH), JBUI.scale(HEIGHT))

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val fill = fill()
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
                    g2.color = borderColor()
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
        /** Opacity of the bookmark border (0–1). */
        const val BORDER_ALPHA = 0.4
        private val LOG = logger<ProjectTabLabel>()
        /** Same size as the close icon, paints nothing: keeps the ×'s room when it is not shown. */
        private val HIDDEN_CLOSE: Icon = EmptyIcon.create(AllIcons.Actions.Close)
        private val SELECTED_BACKGROUND = JBColor.namedColor("MainToolbar.Dropdown.pressedBackground", JBUI.CurrentTheme.ActionButton.pressedBackground())
        private val HOVER_BACKGROUND = JBColor.namedColor("MainToolbar.Dropdown.hoverBackground", JBUI.CurrentTheme.ActionButton.hoverBackground())

        /** Step X6.6: the Agents sidebar's *waiting* colour; `tabs/` does not import `terminal/`, so it is repeated here. */
        private val BADGE_COLOR = JBColor(0xE08855, 0xE08855)
        private const val BADGE_DOT = 6
        private const val BADGE_RING = 1

        /** The badge when tab icons are off: the dot alone, in front of the name. */
        private val LONE_BADGE: Icon = object : Icon {
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = BADGE_COLOR
                    val dot = JBUI.scale(BADGE_DOT)
                    g2.fillOval(x + (iconWidth - dot) / 2, y + (iconHeight - dot) / 2, dot, dot)
                } finally {
                    g2.dispose()
                }
            }

            override fun getIconWidth() = JBUI.scale(BADGE_DOT + 2)
            override fun getIconHeight() = JBUI.scale(BADGE_DOT + 2)
        }

        /**
         * The recent-projects icon for the project at [key] (generated initials when it has none); null if that fails.
         * A linked worktree shows its main checkout's icon, under the name the platform knows that one by
         * ([ProjectNameProvider.iconPathOf]).
         */
        fun projectIcon(key: String, name: String): Icon? = try {
            val owner = ProjectNameProvider.iconPathOf(key)
            RecentProjectsManagerBase.getInstanceEx().getProjectIcon(Path.of(owner ?: key), true, 16, if (owner == null) name else null)
        } catch (e: Exception) {
            LOG.warn("No project icon for $name", e)
            null
        }
    }
}
