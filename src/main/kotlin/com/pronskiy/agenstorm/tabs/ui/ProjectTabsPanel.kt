package com.pronskiy.agenstorm.tabs.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.tabs.ProjectTabsModel
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Steps E1.3/E2.1/P1. The strip itself: one [ProjectTabLabel] per open project in [ProjectTabsModel] order,
 * separators between them and a "+" button. Each frame has its own panel;
 * [ownerProject] (the frame's project) is drawn as the active tab, so no global "current project" bookkeeping
 * is needed. Subscribes to the model while it is showing ([attach] from `addNotify`, [detach] from
 * `removeNotify`). What clicks do is decided by the callbacks (wired by `ProjectTabActions`); the panel renders.
 *
 * Width (P1.1): the strip never measures anything. It reports **preferred** = every tab at its natural width and
 * **minimum** = every tab as an icon, and the main toolbar's compressing layout — which shares the toolbar's width
 * only with components whose minimum is below their preferred — grants it whatever is left once the other groups
 * have theirs, up to preferred. `doLayout` then reads the mode from the width it was given. Nothing here feeds
 * back into the sizes, so there is no loop (E2.1's cap came from reading the parent, whose size came from us).
 * When the full tabs do not fit, the widest give first (P1.2): water-filling finds the level at which every tab
 * wider than it is cut to it and the sum fits, so all shrunk tabs end equal while short names keep theirs, and the
 * strip changes continuously as the window narrows. Below [ProjectTabLabel.MIN_WIDTH] per tab every tab becomes
 * icon-only, and that is the floor (P1.3): nothing ever hides behind a chevron, because the cap on loaded projects
 * (Phase P2) keeps the icon strip small enough for any toolbar; below it the toolbar simply clips.
 *
 * Reordering (E2.2): dragging a tab horizontally past the middle of a neighbour draws an insertion marker and,
 * on release, reports the new index through [onReorder]; the model persists it and every frame follows.
 */
class ProjectTabsPanel(private val model: ProjectTabsModel = ProjectTabsModel.getInstance()) : JPanel(null) {

    enum class Mode { FULL, SHRUNK, COMPACT }

    var ownerProject: Project? = null
        set(value) {
            if (field === value) return
            field = value
            for (label in tabLabels()) label.isSelected = label.project === value
            revalidate()
        }

    /** Left click on a tab. */
    var onSelect: (Project) -> Unit = {}
    /** Middle click on a tab or its × button. */
    var onClose: (Project) -> Unit = {}
    /** The "+" button; receives the button so a popup can anchor to it. */
    var onAdd: (Component) -> Unit = {}
    /** Right click on a tab: the project, the component and the click point, for a popup. */
    var onContextMenu: (Project, Component, Point) -> Unit = { _, _, _ -> }
    /** A tab was dragged to a new position: the project and its new index among the open tabs. */
    var onReorder: (Project, Int) -> Unit = { _, _ -> }

    /** How the strip was laid out last time. */
    var mode: Mode = Mode.FULL
        private set

    private var subscription: Disposable? = null
    private val separators = mutableListOf<JComponent>()

    private var dragSource: ProjectTabLabel? = null
    private var dragStartX = 0
    private var dragging = false
    /** Index (in [tabLabels] order) the dragged tab would be inserted before; null when not dragging. */
    internal var dropIndex: Int? = null
        private set

    private val dragHandler = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            if (e.button != MouseEvent.BUTTON1 || e.isPopupTrigger) return
            dragSource = labelOf(e.component)
            dragStartX = toPanel(e).x
            dragging = false
        }

        override fun mouseDragged(e: MouseEvent) {
            if (dragSource == null) return
            val x = toPanel(e).x
            if (!dragging && kotlin.math.abs(x - dragStartX) < DRAG_THRESHOLD) return
            dragging = true
            val index = insertionIndex(x)
            if (index != dropIndex) {
                dropIndex = index
                repaint()
            }
        }

        override fun mouseReleased(e: MouseEvent) {
            val source = dragSource
            val target = dropIndex
            val wasDragging = dragging
            dragSource = null
            dragging = false
            dropIndex = null
            repaint()
            if (source == null || !wasDragging || target == null) return
            val labels = tabLabels()
            val from = labels.indexOf(source)
            val to = if (target > from) target - 1 else target
            if (from >= 0 && to != from) onReorder(source.project, to)
        }
    }

    internal val addButton = JBLabel(AllIcons.General.Add).apply {
        toolTipText = AgenstormBundle.message("tabs.add.tooltip")
        border = JBUI.Borders.empty(0, 6)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) onAdd(this@apply)
            }
        })
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty()
        showTabs(model.tabs())
    }

    fun tabLabels(): List<ProjectTabLabel> = components.filterIsInstance<ProjectTabLabel>()

    override fun addNotify() {
        super.addNotify()
        attach()
    }

    override fun removeNotify() {
        detach()
        super.removeNotify()
    }

    /** Starts following the model; idempotent. */
    fun attach() {
        if (subscription != null) return
        val disposable = Disposer.newDisposable("Agenstorm project tabs panel")
        subscription = disposable
        model.addListener({ tabs -> showTabs(tabs) }, disposable)
        showTabs(model.tabs())
    }

    fun detach() {
        subscription?.let(Disposer::dispose)
        subscription = null
    }

    /** Rebuilds the strip for [tabs]; the layout pass decides what fits. */
    internal fun showTabs(tabs: List<Project>) {
        removeAll()
        separators.clear()
        dragSource = null
        dropIndex = null
        for (project in tabs) {
            val label = ProjectTabLabel(
                project,
                selected = project === ownerProject,
                onSelect = { onSelect(project) },
                onClose = { onClose(project) },
                onContextMenu = { component, point -> onContextMenu(project, component, point) },
            )
            label.addDragListener(dragHandler)
            add(label)
        }
        repeat((tabs.size - 1).coerceAtLeast(0)) { separators += separator().also(::add) }
        add(addButton)
        revalidate()
        repaint()
    }

    // ---- layout -------------------------------------------------------------------------------------------------

    /** [widths] are the tabs' planned widths, in [tabLabels] order. */
    private class Plan(val mode: Mode, val width: Int, val widths: List<Int>)

    /** Width of a strip whose tabs are [widths] wide: separators between them and the "+". */
    private fun total(widths: List<Int>): Int {
        val gap = JBUI.scale(2)
        val separator = JBUI.scale(1) + gap
        val fixed = addButton.preferredSize.width + gap
        return widths.sum() + separator * (widths.size - 1).coerceAtLeast(0) + fixed
    }

    /** Every tab at its natural width: what the strip asks the toolbar for. */
    private fun fullWidth(): Int = total(tabLabels().map { it.preferredWidth(false) })

    /** Every tab as an icon: the least the strip lays out. */
    private fun compactWidth(): Int = total(tabLabels().map { it.preferredWidth(true) })

    /** Chooses the mode and the tab widths for [available] pixels. */
    private fun plan(available: Int): Plan {
        val labels = tabLabels()
        val natural = labels.map { it.preferredWidth(false) }
        val full = fullWidth()
        if (labels.isEmpty() || available <= 0 || full <= available) return Plan(Mode.FULL, full, natural)
        val level = shrinkLevel(natural, available - total(List(labels.size) { 0 }))
        if (level != null) {
            val widths = natural.map { minOf(it, level) }
            return Plan(Mode.SHRUNK, total(widths), widths)
        }
        return Plan(Mode.COMPACT, compactWidth(), labels.map { it.preferredWidth(true) })
    }

    /**
     * The width every tab wider than it is cut to so that the tabs fit [budget] pixels, or null when that would
     * take a tab below [ProjectTabLabel.MIN_WIDTH]. Tabs no wider than the level keep their [natural] width; the
     * level only rises as more of them are found, so the loop ends when a pass changes nothing.
     */
    private fun shrinkLevel(natural: List<Int>, budget: Int): Int? {
        if (natural.isEmpty() || budget < JBUI.scale(ProjectTabLabel.MIN_WIDTH) * natural.size) return null
        var level = budget / natural.size
        while (true) {
            val kept = natural.filter { it <= level }
            val cut = natural.size - kept.size
            if (cut == 0) return level
            val next = (budget - kept.sum()) / cut
            if (next == level) return level
            level = next
        }
    }

    override fun getPreferredSize(): Dimension {
        if (isPreferredSizeSet) return super.getPreferredSize()
        return Dimension(fullWidth(), JBUI.scale(ProjectTabLabel.HEIGHT))
    }

    /** Below preferred on purpose: that is what makes the toolbar's layout share its width with the strip. */
    override fun getMinimumSize(): Dimension = Dimension(compactWidth(), JBUI.scale(ProjectTabLabel.HEIGHT))

    override fun getMaximumSize(): Dimension = preferredSize

    override fun doLayout() {
        val plan = plan(width)
        mode = plan.mode
        val compact = plan.mode == Mode.COMPACT
        val gap = JBUI.scale(2)
        var x = 0
        for ((index, label) in tabLabels().withIndex()) {
            label.isCompact = compact
            label.isVisible = true
            separators.getOrNull(index - 1)?.let { separator ->
                separator.isVisible = true
                place(separator, x)
                x += separator.preferredSize.width + gap
            }
            val tabWidth = plan.widths[index]
            place(label, x, tabWidth)
            x += tabWidth
        }
        x += gap
        place(addButton, x)
    }

    override fun paint(g: java.awt.Graphics) {
        super.paint(g)
        val index = dropIndex ?: return
        val labels = tabLabels()
        val x = when {
            labels.isEmpty() -> 0
            index >= labels.size -> labels.last().let { it.x + it.width }
            else -> labels[index].x
        }
        g.color = JBUI.CurrentTheme.Focus.focusColor()
        g.fillRect((x - JBUI.scale(1)).coerceAtLeast(0), JBUI.scale(3), JBUI.scale(2), height - JBUI.scale(6))
    }

    /** Index the pointer at panel-x [x] points at: before the first tab whose centre lies right of it. */
    internal fun insertionIndex(x: Int): Int {
        val labels = tabLabels()
        for ((index, label) in labels.withIndex()) {
            if (x < label.x + label.width / 2) return index
        }
        return labels.size
    }

    private fun labelOf(component: Component): ProjectTabLabel? =
        generateSequence(component) { it.parent }.filterIsInstance<ProjectTabLabel>().firstOrNull()

    private fun toPanel(e: MouseEvent): Point = SwingUtilities.convertPoint(e.component, e.point, this)

    private fun place(component: Component, x: Int, width: Int = component.preferredSize.width) {
        val height = component.preferredSize.height
        component.setBounds(x, (this.height - height) / 2, width, height)
    }

    companion object {
        private val DRAG_THRESHOLD = JBUI.scale(4)
    }

    private fun separator(): JComponent = JPanel().apply {
        isOpaque = true
        background = JBUI.CurrentTheme.MainToolbar.borderColor()
        val size = Dimension(JBUI.scale(1), JBUI.scale(16))
        preferredSize = size
        minimumSize = size
        maximumSize = size
    }
}
