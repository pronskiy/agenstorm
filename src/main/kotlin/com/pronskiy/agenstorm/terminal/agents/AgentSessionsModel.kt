package com.pronskiy.agenstorm.terminal.agents

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Steps X1.2 and X2.4. The one model every window's Agents sidebar draws (decision 102): a group per open project, in
 * the same order everywhere. Refreshed when a project opens ([AgentsStartupActivity]) or closes ([AgentsProjectClosed]);
 * the settings page turns the sidebar on and off in every window through [AgentsToolWindowFactory.syncAvailability].
 */
@Service(Service.Level.APP)
class AgentSessionsModel(private val scope: CoroutineScope) : Disposable {

    private val groupsFlow = MutableStateFlow<List<ProjectGroup>>(emptyList())

    val groups: StateFlow<List<ProjectGroup>> = groupsFlow.asStateFlow()

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { state -> AgentsToolWindowFactory.syncAvailability(state.agentSessionsEnabled) })
    }

    /** Re-reads the open projects, leaving out [closing] — a project being closed may still be listed. Any thread. */
    fun refresh(closing: Project? = null) {
        val open = ProjectManager.getInstance().openProjects.filter { it != closing && !it.isDisposed && !it.isDefault }
        groupsFlow.value = ProjectGroup.sorted(open.mapNotNull { project -> project.basePath?.let { ProjectGroup(project.name, it) } })
    }

    /** Calls [render] on the EDT with the groups now and after every change, until [parent] is disposed. */
    fun collect(parent: Disposable, render: (List<ProjectGroup>) -> Unit) = follow(groups, parent, render)

    /** Calls [render] on the EDT with [flow]'s value now and after every change, until [parent] is disposed. */
    fun <T> follow(flow: StateFlow<T>, parent: Disposable, render: (T) -> Unit) {
        val job = scope.launch(Dispatchers.EDT) { flow.collect { render(it) } }
        Disposer.register(parent) { job.cancel() }
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(): AgentSessionsModel = service()
    }
}
