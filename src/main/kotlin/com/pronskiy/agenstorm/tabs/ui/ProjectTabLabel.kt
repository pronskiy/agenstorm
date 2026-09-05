package com.pronskiy.agenstorm.tabs.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.Graphics
import java.awt.Graphics2D
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
 * something sensible.
 */
class ProjectTabLabel(
    val project: Project,
    selected: Boolean,
    private val onSelect: () -> Unit,
    private val onClose: () -> Unit,
    private val onContextMenu: (Component, Point) -> Unit = { _, _ -> },
) : JPanel(BorderLayout(JBUI.scale(4), 0)) {

    var isSelected: Boolean = selected
        set(value) {
            if (field == value) return
            field = value
            nameLabel.foreground = textColor()
            updateCloseIcon()
            repaint()
        }

    private var hovered = false
    private val nameLabel = JBLabel(project.name, projectIcon(project), JBLabel.LEFT)

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
        toolTipText = project.basePath ?: project.name
        nameLabel.foreground = textColor()
        nameLabel.iconTextGap = JBUI.scale(6)
        updateCloseIcon()
        add(nameLabel, BorderLayout.CENTER)
        add(closeLabel, BorderLayout.EAST)
        alignmentY = CENTER_ALIGNMENT
        val mouse = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = setHovered(true)
            override fun mouseExited(e: MouseEvent) {
                if (!contains(e.point)) setHovered(false)
            }
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

    private fun textColor() = if (isSelected) JBColor.namedColor("MainToolbar.Dropdown.foreground", UIUtil.getLabelForeground()) else NamedColorUtil.getInactiveTextColor()

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        size.width = size.width.coerceIn(JBUI.scale(MIN_WIDTH), JBUI.scale(MAX_WIDTH))
        size.height = JBUI.scale(HEIGHT)
        return size
    }

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(MIN_WIDTH), JBUI.scale(HEIGHT))

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val fill = when {
            isSelected -> SELECTED_BACKGROUND
            hovered -> HOVER_BACKGROUND
            else -> null
        }
        if (fill != null) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = fill
                val arc = JBUI.CurrentTheme.MainToolbar.Dropdown.hoverArc().get()
                val inset = JBUI.scale(2)
                g2.fillRoundRect(0, inset, width, height - 2 * inset, arc, arc)
            } finally {
                g2.dispose()
            }
        }
        super.paintComponent(g)
    }

    companion object {
        const val MIN_WIDTH = 72
        const val MAX_WIDTH = 220
        const val HEIGHT = 30
        private val LOG = logger<ProjectTabLabel>()
        /** Same size as the close icon, paints nothing: keeps the ×'s room when it is not shown. */
        private val HIDDEN_CLOSE: Icon = EmptyIcon.create(AllIcons.Actions.Close)
        private val SELECTED_BACKGROUND = JBColor.namedColor("MainToolbar.Dropdown.pressedBackground", JBUI.CurrentTheme.ActionButton.pressedBackground())
        private val HOVER_BACKGROUND = JBColor.namedColor("MainToolbar.Dropdown.hoverBackground", JBUI.CurrentTheme.ActionButton.hoverBackground())

        /** The recent-projects icon for the project (generated initials when it has none); null if that fails. */
        fun projectIcon(project: Project): Icon? {
            val basePath = project.basePath ?: return null
            return try {
                RecentProjectsManagerBase.getInstanceEx().getProjectIcon(Path.of(basePath), true, 16, project.name)
            } catch (e: Exception) {
                LOG.warn("No project icon for ${project.name}", e)
                null
            }
        }
    }
}
