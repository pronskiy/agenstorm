package com.pronskiy.agenstorm.terminal.agents

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import com.pronskiy.agenstorm.terminal.tmux.BackgroundTerminals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Steps X1.2 and X2.4. The one model every window's Agents sidebar draws (decision 102): a group per open project, in
 * the same order everywhere, with the Claude Code sessions running in it and where each can be shown. While the setting
 * is on it reads `~/.claude/sessions` every [POLL], and at once when a project opens ([AgentsStartupActivity]) or closes
 * ([AgentsProjectClosed]) and when a Terminal tab is added ([AgentsTabsListener]); with the setting off it reads nothing
 * there. The settings page turns the sidebar on and off in every window through [AgentsToolWindowFactory.syncAvailability].
 */
@Service(Service.Level.APP)
class AgentSessionsModel(private val scope: CoroutineScope) : Disposable {

    private val groupsFlow = MutableStateFlow<List<ProjectGroup>>(emptyList())
    private val passes = Mutex()
    private val finished = FinishedTracker()

    /** `~/.claude`; tests point it elsewhere. */
    internal var claudeHome: () -> Path = ClaudeLiveSessions::home

    val groups: StateFlow<List<ProjectGroup>> = groupsFlow.asStateFlow()

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { state ->
                AgentsToolWindowFactory.syncAvailability(state.agentSessionsEnabled)
                refresh()
            })
        scope.launch {
            while (isActive) {
                if (enabled()) pass()
                delay(POLL)
            }
        }
    }

    /**
     * The open projects now — leaving out [closing], which may still be listed — each keeping the sessions it had, then a
     * read of the sessions in the background. Any thread.
     */
    fun refresh(closing: Project? = null) {
        val before = groupsFlow.value.associateBy { it.basePath }
        groupsFlow.value = ProjectGroup.sorted(openGroups(closing).map { it.copy(sessions = before[it.basePath]?.sessions.orEmpty()) })
        scope.launch { pass(closing) }
    }

    /** The user clicked [sessionId]: its dot goes. */
    fun seen(sessionId: String) {
        finished.seen(sessionId)
        groupsFlow.value = groupsFlow.value.map { group ->
            group.copy(sessions = group.sessions.map { if (it.session.sessionId == sessionId) it.copy(finished = false) else it })
        }
    }

    private suspend fun pass(closing: Project? = null) = passes.withLock {
        val on = enabled()
        val (projects, probes, front) = withContext(Dispatchers.EDT) {
            Triple(openGroups(closing), if (on) AgentTabLocator.probes() else emptyList(), AgentsSidebarSync.front()?.basePath)
        }
        if (!on) {
            groupsFlow.value = ProjectGroup.sorted(projects)
            return@withLock
        }
        val rows = withContext(Dispatchers.IO) {
            val live = ClaudeLiveSessions.read(claudeHome())
            val background = BackgroundTerminals.getInstance().sessions.value.mapTo(HashSet()) { it.name }
            val places = AgentTabLocator.locateAll(live, probes, background, ProcessTree.snapshot())
            val grouped = SessionGrouping.group(live, projects.map { it.basePath })
            val done = finished.update(grouped.flatMap { (base, sessions) -> sessions.map { it to base } }, front)
            grouped.mapValues { (_, sessions) ->
                sessions.map { SessionRow(it, places[it.sessionId] ?: SessionPlace.Elsewhere, it.sessionId in done) }
            }
        }
        groupsFlow.value = ProjectGroup.sorted(projects.map { it.copy(sessions = rows[it.basePath].orEmpty()) })
    }

    private fun openGroups(closing: Project?): List<ProjectGroup> =
        ProjectManager.getInstance().openProjects.filter { it != closing && !it.isDisposed && !it.isDefault }
            .mapNotNull { project -> project.basePath?.let { ProjectGroup(project.name, it) } }

    private fun enabled() = AgenstormSettings.getInstance().state.agentSessionsEnabled

    /** Calls [render] on the EDT with the groups now and after every change, until [parent] is disposed. */
    fun collect(parent: Disposable, render: (List<ProjectGroup>) -> Unit) = follow(groups, parent, render)

    /** Calls [render] on the EDT with [flow]'s value now and after every change, until [parent] is disposed. */
    fun <T> follow(flow: StateFlow<T>, parent: Disposable, render: (T) -> Unit) {
        val job = scope.launch(Dispatchers.EDT) { flow.collect { render(it) } }
        Disposer.register(parent) { job.cancel() }
    }

    override fun dispose() = Unit

    companion object {
        private val POLL = 2.seconds

        fun getInstance(): AgentSessionsModel = service()
    }
}

/** Step X2.4. The processes under a shell, from one look at the process table per pass, taken only if someone asks. */
internal object ProcessTree {

    fun snapshot(): (Long) -> Set<Long> {
        val children by lazy {
            ProcessHandle.allProcesses().toList().mapNotNull { handle -> handle.parent().orElse(null)?.let { it.pid() to handle.pid() } }
                .groupBy({ it.first }, { it.second })
        }
        return { pid ->
            val found = HashSet<Long>()
            val stack = ArrayDeque(children[pid].orEmpty())
            while (stack.isNotEmpty()) {
                val next = stack.removeLast()
                if (found.add(next)) stack.addAll(children[next].orEmpty())
            }
            found
        }
    }
}
