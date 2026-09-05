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
 * Steps E1.3/E2.1. The strip itself: one [ProjectTabLabel] per open project in [ProjectTabsModel] order,
 * separators between them, a chevron for tabs that do not fit and a "+" button. Each frame has its own panel;
 * [ownerProject] (the frame's project) is drawn as the active tab, so no global "current project" bookkeeping
 * is needed. Subscribes to the model while it is showing ([attach] from `addNotify`, [detach] from
 * `removeNotify`). What clicks do is decided by the callbacks (wired by `ProjectTabActions`); the panel renders.
 *
 * Overflow: the strip may take at most half of the window it sits in ([availableWidthProvider]). The window is
 * the reference on purpose: the left main-toolbar group is sized from its children, so measuring against it
 * would shrink the cap together with the strip until only icons were left. When the full tabs do not fit, every
 * tab becomes icon-only; when even that does not fit, the first N icon-only tabs are shown (the frame's own
 * project always among them) and the rest hide behind the chevron, which lists them in a popup.
 *
 * Reordering (E2.2): dragging a tab horizontally past the middle of a neighbour draws an insertion marker and,
 * on release, reports the new index through [onReorder]; the model persists it and every frame follows.
 */
class ProjectTabsPanel(private val model: ProjectTabsModel = ProjectTabsModel.getInstance()) : JPanel(null) {

    enum class Mode { FULL, COMPACT, OVERFLOW }

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
    /** The chevron; receives the button and the projects that do not fit, for a popup. */
    var onOverflow: (Component, List<Project>) -> Unit = { _, _ -> }
    /** A tab was dragged to a new position: the project and its new index among the open tabs. */
    var onReorder: (Project, Int) -> Unit = { _, _ -> }

    /** Pixels the strip may use; by default half of the enclosing window, unlimited before the window is sized. */
    var availableWidthProvider: () -> Int = { availableWidthFor(SwingUtilities.getWindowAncestor(this)?.width ?: 0) }

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
    internal val moreButton = JBLabel(AllIcons.Actions.MoreHorizontal).apply {
        border = JBUI.Borders.empty(0, 4)
        isVisible = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) onOverflow(this@apply, hiddenProjects())
            }
        })
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty()
        showTabs(model.tabs())
    }

    fun tabLabels(): List<ProjectTabLabel> = components.filterIsInstance<ProjectTabLabel>()

    /** Projects whose tabs are currently behind the chevron. */
    fun hiddenProjects(): List<Project> = tabLabels().filter { !it.isVisible }.map { it.project }

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
        add(moreButton)
        add(addButton)
        revalidate()
        repaint()
    }

    // ---- layout -------------------------------------------------------------------------------------------------

    private class Plan(val mode: Mode, val visible: List<ProjectTabLabel>, val width: Int)

    /** Chooses the mode and the visible tabs for [available] pixels. */
    private fun plan(available: Int): Plan {
        val labels = tabLabels()
        val gap = JBUI.scale(2)
        val separator = JBUI.scale(1) + gap
        val fixed = addButton.preferredSize.width + gap
        fun total(widths: List<Int>, more: Boolean) =
            widths.sum() + separator * (widths.size - 1).coerceAtLeast(0) + fixed + if (more) moreButton.preferredSize.width + gap else 0

        val full = total(labels.map { it.preferredWidth(false) }, more = false)
        if (labels.isEmpty() || available <= 0 || full <= available) return Plan(Mode.FULL, labels, full)
        val compact = total(labels.map { it.preferredWidth(true) }, more = false)
        if (compact <= available) return Plan(Mode.COMPACT, labels, compact)

        val each = JBUI.scale(ProjectTabLabel.COMPACT_WIDTH)
        var count = 1
        while (count < labels.size && total(List(count + 1) { each }, more = true) <= available) count++
        val visible = labels.take(count).toMutableList()
        val owner = labels.firstOrNull { it.project === ownerProject }
        if (owner != null && owner !in visible) visible[visible.lastIndex] = owner
        return Plan(Mode.OVERFLOW, visible, total(List(count) { each }, more = true))
    }

    override fun getPreferredSize(): Dimension {
        if (isPreferredSizeSet) return super.getPreferredSize()
        val plan = plan(availableWidthProvider())
        return Dimension(plan.width, JBUI.scale(ProjectTabLabel.HEIGHT))
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun doLayout() {
        val plan = plan(if (width > 0) minOf(width, availableWidthProvider()) else availableWidthProvider())
        mode = plan.mode
        val compact = plan.mode != Mode.FULL
        val gap = JBUI.scale(2)
        val labels = tabLabels()
        var x = 0
        var shown = 0
        for ((index, label) in labels.withIndex()) {
            val separator = separators.getOrNull(index - 1)
            val visible = label in plan.visible
            label.isCompact = compact
            label.isVisible = visible
            separator?.isVisible = false
            if (!visible) continue
            if (shown > 0 && separator != null) {
                separator.isVisible = true
                place(separator, x)
                x += separator.preferredSize.width + gap
            }
            place(label, x)
            x += label.preferredSize.width
            shown++
        }
        moreButton.isVisible = plan.mode == Mode.OVERFLOW
        if (moreButton.isVisible) {
            val hidden = labels.size - plan.visible.size
            moreButton.toolTipText = AgenstormBundle.message("tabs.more.tooltip", hidden)
            x += gap
            place(moreButton, x)
            x += moreButton.preferredSize.width
        }
        x += gap
        place(addButton, x)
    }

    override fun paint(g: java.awt.Graphics) {
        super.paint(g)
        val index = dropIndex ?: return
        val visible = tabLabels().filter { it.isVisible }
        val x = when {
            visible.isEmpty() -> 0
            index >= tabLabels().size -> visible.last().let { it.x + it.width }
            else -> (tabLabels()[index].takeIf { it.isVisible } ?: visible.last()).x
        }
        g.color = JBUI.CurrentTheme.Focus.focusColor()
        g.fillRect((x - JBUI.scale(1)).coerceAtLeast(0), JBUI.scale(3), JBUI.scale(2), height - JBUI.scale(6))
    }

    /** Index the pointer at panel-x [x] points at: before the first visible tab whose centre lies right of it. */
    internal fun insertionIndex(x: Int): Int {
        val labels = tabLabels()
        for ((index, label) in labels.withIndex()) {
            if (label.isVisible && x < label.x + label.width / 2) return index
        }
        return labels.size
    }

    private fun labelOf(component: Component): ProjectTabLabel? =
        generateSequence(component) { it.parent }.filterIsInstance<ProjectTabLabel>().firstOrNull()

    private fun toPanel(e: MouseEvent): Point = SwingUtilities.convertPoint(e.component, e.point, this)

    private fun place(component: Component, x: Int) {
        val size = component.preferredSize
        component.setBounds(x, (height - size.height) / 2, size.width, size.height)
    }

    companion object {
        private val DRAG_THRESHOLD = JBUI.scale(4)

        /** Half of a sized window; no cap while the window has no size yet (or the panel is not in one). */
        fun availableWidthFor(windowWidth: Int): Int = if (windowWidth > 0) windowWidth / 2 else Int.MAX_VALUE
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
