package com.pronskiy.agenstorm.terminal.agents

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step X1.2. The Agents tool window, one per frame (decision 102), available only while the setting is on (decision
 * 104); its panel draws the app-level [AgentSessionsModel], its layout follows [AgentsSidebarSync].
 */
class AgentsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean = AgenstormSettings.getInstance().state.agentSessionsEnabled

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AgentsPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
        AgentSessionsModel.getInstance().refresh()
    }

    companion object {
        const val ID: String = "Agenstorm.Agents"

        /** The setting changed: every open project's Agents tool window follows it. */
        fun syncAvailability(enabled: Boolean) {
            ApplicationManager.getApplication().invokeLater {
                for (project in ProjectManager.getInstance().openProjects) {
                    if (project.isDisposed) continue
                    val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: continue
                    window.isAvailable = enabled
                    if (enabled) AgentsSidebarSync.getInstance().apply(project)
                }
            }
        }
    }
}
