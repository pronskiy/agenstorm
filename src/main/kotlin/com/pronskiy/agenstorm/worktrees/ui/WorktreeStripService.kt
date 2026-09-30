package com.pronskiy.agenstorm.worktrees.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import com.pronskiy.agenstorm.worktrees.WorktreeRegistry
import com.pronskiy.agenstorm.worktrees.WorktreeSwitcher
import com.pronskiy.agenstorm.worktrees.WorktreeTab
import com.pronskiy.agenstorm.worktrees.WorktreeTabsModel
import com.pronskiy.agenstorm.worktrees.create.NewWorktreeFlow
import com.pronskiy.agenstorm.worktrees.status.WorktreeStatusService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.HierarchyEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Step T1.4 (decision 82). Feeds the strip of this project from [WorktreeRegistry] — and, since T3.3, the tabs' badges
 * from [WorktreeStatusService], which it tells whether a strip is on screen — and puts it above the Project tree:
 * the Project view's root is a `SimpleToolWindowPanel` whose toolbar slot `ProjectViewImpl` never uses (262 and 263),
 * so the strip becomes that toolbar — no content is wrapped or replaced, and a recreated panel gets a new strip on the
 * next tool window state change. A click hands the switch to [WorktreeSwitcher] (T1.6).
 */
@Service(Service.Level.PROJECT)
class WorktreeStripService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    private val panels = CopyOnWriteArrayList<WorktreeStripPanel>()
    private val started = AtomicBoolean()
    private var tabs: List<WorktreeTab> = emptyList()
    private var badges: Map<String, TabBadge> = emptyMap()
    private var host: SimpleToolWindowPanel? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val current = project.basePath?.let(FileUtil::toSystemIndependentName)
            combine(WorktreeRegistry.getInstance(project).state, WorktreeStatusService.getInstance(project).state) { snapshot, statuses ->
                val next = WorktreeTabsModel.tabs(snapshot, current)
                next to next.associate { it.path to TabBadge.of(it.worktree, statuses.git[it.path], statuses.busy[it.path]) }
            }.collect { (nextTabs, nextBadges) ->
                withContext(Dispatchers.EDT) {
                    tabs = nextTabs
                    badges = nextBadges
                    render()
                }
            }
        }
        project.messageBus.connect(this).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) = installAboveTree()
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { render() })
        ToolWindowManager.getInstance(project).invokeLater { installAboveTree() }
    }

    private fun newPanel(): WorktreeStripPanel =
        WorktreeStripPanel(
            onSelect = ::switchTo,
            onAdd = { NewWorktreeFlow.start(project) },
            onContextMenu = { tab, component, point -> WorktreeStripActions.showContextMenu(project, tab, component, point) },
        ).also { panel ->
            panels += panel
            panel.show(visibleTabs(), badges)
            panel.addHierarchyListener { event ->
                if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                    WorktreeStatusService.getInstance(project).setShowing(panels.any { it.isShowing })
                }
            }
        }

    /** Step T1.8: with the feature off the strip shows no tabs, which hides it; on again, it comes back without a restart. */
    private fun visibleTabs(): List<WorktreeTab> = if (AgenstormSettings.getInstance().state.worktreesEnabled) tabs else emptyList()

    private fun render() {
        val shown = visibleTabs()
        panels.forEach { it.show(shown, badges) }
    }

    private fun installAboveTree() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW) ?: return
        val content = toolWindow.contentManagerIfCreated?.contents?.firstOrNull() ?: return
        val panel = UIUtil.findComponentOfType(content.component, SimpleToolWindowPanel::class.java) ?: return
        if (panel === host || panel.toolbar != null) return
        host?.let { old -> if (old.toolbar is WorktreeStripPanel) old.toolbar = null }
        panel.toolbar = newPanel()
        host = panel
    }

    /** The switch runs in the application's [WorktreeSwitcher]: it closes this project, and this service with it. */
    private fun switchTo(tab: WorktreeTab) = WorktreeSwitcher.getInstance().switch(project, tab.path)

    override fun dispose() {
        host?.let { if (it.toolbar is WorktreeStripPanel) it.toolbar = null }
        host = null
        panels.clear()
    }

    companion object {
        fun getInstance(project: Project): WorktreeStripService = project.service()
    }
}
