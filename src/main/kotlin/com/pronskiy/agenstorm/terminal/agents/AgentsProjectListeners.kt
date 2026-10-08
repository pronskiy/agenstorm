package com.pronskiy.agenstorm.terminal.agents

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.startup.ProjectActivity

/** Step X1.2. A project that opens joins every window's Agents sidebar. */
class AgentsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) = AgentSessionsModel.getInstance().refresh()
}

/** Step X1.2. A project that closes leaves every window's Agents sidebar. */
class AgentsProjectClosed : ProjectCloseListener {
    override fun projectClosed(project: Project) = AgentSessionsModel.getInstance().refresh(closing = project)
}
