package com.pronskiy.agenstorm.terminal.agents

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step X1.4. [SidebarSyncer] over the IDE's windows: a project is a window, the one in front is the last focused frame's,
 * and a step runs on the project's Agents tool window. EDT.
 */
@Service(Service.Level.APP)
class AgentsSidebarSync {

    private val syncer = SidebarSyncer<Project>(
        state = AgentsSidebarState.getInstance(),
        windows = { project -> toolWindow(project)?.let(::ToolWindowSidebar) },
        front = ::front,
        all = { ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault } },
        enabled = { AgenstormSettings.getInstance().state.agentSessionsEnabled },
        later = { project, action -> ApplicationManager.getApplication().invokeLater(action, project.disposed) },
    )

    fun record(project: Project) {
        if (!project.isDisposed) syncer.record(project)
    }

    fun apply(project: Project) {
        if (!project.isDisposed) syncer.apply(project)
    }

    fun forget(project: Project) = syncer.forget(project)

    private class ToolWindowSidebar(private val window: ToolWindow) : SidebarWindow {
        override val available: Boolean get() = window.isAvailable

        override fun layout() = FrameLayout(window.isVisible, window.component.width, window.anchor.toString())

        override fun run(step: SidebarStep) = when (step) {
            is SidebarStep.SetAnchor -> window.setAnchor(ToolWindowAnchor.fromText(step.anchor), null)
            SidebarStep.Show -> window.show()
            SidebarStep.Hide -> window.hide()
            is SidebarStep.Stretch -> (window as? ToolWindowEx)?.stretchWidth(step.delta) ?: Unit
        }
    }

    companion object {
        fun getInstance(): AgentsSidebarSync = service()

        fun toolWindow(project: Project): ToolWindow? =
            if (project.isDisposed) null else ToolWindowManager.getInstance(project).getToolWindow(AgentsToolWindowFactory.ID)

        /** The project of the window that had the focus last: the one the user is working in. */
        fun front(): Project? = IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project

        fun isFront(project: Project): Boolean = front() == project
    }
}

/** Step X1.4. Shows, hides, resizes and moves of a window's Agents sidebar are recorded — from the window in front only. */
class AgentsToolWindowListener(private val project: Project) : ToolWindowManagerListener {

    override fun stateChanged(toolWindowManager: ToolWindowManager, changeType: ToolWindowManagerListener.ToolWindowManagerEventType) {
        if (toolWindowManager.getToolWindow(AgentsToolWindowFactory.ID) != null) AgentsSidebarSync.getInstance().record(project)
    }

    override fun toolWindowShown(toolWindow: ToolWindow) {
        if (toolWindow.id == AgentsToolWindowFactory.ID) AgentsSidebarSync.getInstance().record(project)
    }
}
