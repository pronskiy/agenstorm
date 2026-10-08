package com.pronskiy.agenstorm.terminal.agents

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.terminal.frontend.toolwindow.TerminalTabsManagerListener
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Steps X1.2 and X1.4. A project that opens joins every window's Agents sidebar, and its own takes the shared layout. */
class AgentsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        AgentSessionsModel.getInstance().refresh()
        logger<AgentsStartupActivity>().debug { "startup ${project.name}" }
        withContext(Dispatchers.EDT) { AgentsSidebarSync.getInstance().apply(project) }
    }
}

/** Step X1.2. A project that closes leaves every window's Agents sidebar. */
class AgentsProjectClosed : ProjectCloseListener {
    override fun projectClosed(project: Project) {
        AgentSessionsModel.getInstance().refresh(closing = project)
        AgentsSidebarSync.getInstance().forget(project)
    }
}

/** Step X2.4. A new Terminal tab may be where a session runs: read again now rather than at the next poll. */
class AgentsTabsListener : TerminalTabsManagerListener {
    override fun tabAdded(tab: TerminalToolWindowTab) = AgentSessionsModel.getInstance().refresh()
}
