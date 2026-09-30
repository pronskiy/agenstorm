package com.pronskiy.agenstorm.worktrees.ui

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.Disposable
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
import com.pronskiy.agenstorm.core.AgenstormAppScope
import com.pronskiy.agenstorm.worktrees.WorktreeRegistry
import com.pronskiy.agenstorm.worktrees.WorktreeTab
import com.pronskiy.agenstorm.worktrees.WorktreeTabsModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Step T1.4 (decision 82). Feeds the strip of this project from [WorktreeRegistry] and puts it above the Project tree:
 * the Project view's root is a `SimpleToolWindowPanel` whose toolbar slot `ProjectViewImpl` never uses (262 and 263),
 * so the strip becomes that toolbar — no content is wrapped or replaced, and a recreated panel gets a new strip on the
 * next tool window state change. A click opens the worktree through the platform for now; the real swap, which does
 * not depend on the user's "open project in" choice, and the busy rule are T1.6.
 */
@Service(Service.Level.PROJECT)
class WorktreeStripService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    private val panels = CopyOnWriteArrayList<WorktreeStripPanel>()
    private val started = AtomicBoolean()
    private var tabs: List<WorktreeTab> = emptyList()
    private var host: SimpleToolWindowPanel? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            WorktreeRegistry.getInstance(project).state.collect { snapshot ->
                val next = WorktreeTabsModel.tabs(snapshot, project.basePath?.let(FileUtil::toSystemIndependentName))
                withContext(Dispatchers.EDT) {
                    tabs = next
                    panels.forEach { it.show(next) }
                }
            }
        }
        project.messageBus.connect(this).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) = installAboveTree()
        })
        ToolWindowManager.getInstance(project).invokeLater { installAboveTree() }
    }

    private fun newPanel(): WorktreeStripPanel =
        WorktreeStripPanel(::switchTo, onAdd = {}).also {
            panels += it
            it.show(tabs)
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

    /**
     * Interim until T1.6. Launched in the application scope, never this service's: if the platform reuses this window
     * it closes this project, which cancels the project's scope and joins its children — a child waiting in
     * `openOrImportAsync` would wait for itself (the hang `OpenRequestServer.openProject` documents).
     */
    private fun switchTo(tab: WorktreeTab) {
        service<AgenstormAppScope>().scope.launch(Dispatchers.EDT) {
            ProjectUtil.openOrImportAsync(Path.of(tab.path), OpenProjectTask.build().withProjectToClose(project))
        }
    }

    override fun dispose() {
        host?.let { if (it.toolbar is WorktreeStripPanel) it.toolbar = null }
        host = null
        panels.clear()
    }

    companion object {
        fun getInstance(project: Project): WorktreeStripService = project.service()
    }
}
