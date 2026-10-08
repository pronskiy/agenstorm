package com.pronskiy.agenstorm.terminal.agents

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Steps X1.2 and X1.4. A project that opens joins every window's Agents sidebar, and its own takes the shared layout. */
class AgentsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        AgentSessionsModel.getInstance().refresh()
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
