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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Step E1.3. The strip itself: one [ProjectTabLabel] per open project in [ProjectTabsModel] order, separators
 * between them and a "+" button. Each frame has its own panel; [ownerProject] (the frame's project) is drawn as
 * the active tab, so no global "current project" bookkeeping is needed. Subscribes to the model while it is
 * showing ([attach] from `addNotify`, [detach] from `removeNotify`). What clicks do is decided by the callbacks
 * (wired in E1.4); the panel only renders.
 */
class ProjectTabsPanel(private val model: ProjectTabsModel = ProjectTabsModel.getInstance()) : JPanel() {

    var ownerProject: Project? = null
        set(value) {
            if (field === value) return
            field = value
            for (label in tabLabels()) label.isSelected = label.project === value
        }

    /** Left click on a tab. */
    var onSelect: (Project) -> Unit = {}
    /** Middle click on a tab or its × button. */
    var onClose: (Project) -> Unit = {}
    /** The "+" button; receives the button so a popup can anchor to it. */
    var onAdd: (Component) -> Unit = {}

    private var subscription: Disposable? = null
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
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty()
        rebuild(model.tabs())
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
        model.addListener({ tabs -> rebuild(tabs) }, disposable)
        rebuild(model.tabs())
    }

    fun detach() {
        subscription?.let(Disposer::dispose)
        subscription = null
    }

    private fun rebuild(tabs: List<Project>) {
        removeAll()
        tabs.forEachIndexed { index, project ->
            if (index > 0) add(separator())
            add(ProjectTabLabel(project, selected = project === ownerProject, onSelect = { onSelect(project) }, onClose = { onClose(project) }))
        }
        add(Box.createHorizontalStrut(JBUI.scale(2)))
        add(addButton)
        revalidate()
        repaint()
    }

    private fun separator(): Component = JPanel().apply {
        isOpaque = true
        background = JBUI.CurrentTheme.MainToolbar.borderColor()
        val size = Dimension(JBUI.scale(1), JBUI.scale(16))
        preferredSize = size
        minimumSize = size
        maximumSize = size
        alignmentY = CENTER_ALIGNMENT
    }
}
