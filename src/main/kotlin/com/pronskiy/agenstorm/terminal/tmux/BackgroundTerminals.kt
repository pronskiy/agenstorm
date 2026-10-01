package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.ide.ActivityTracker
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.GitCommonDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Step U3.3 (decision 91). The background terminals — sessions kept running past their last tab on a close or a quit
 * (U3.2) — as the IDE knows them: read from tmux every [POLL] while the setting is on, and at once after anything
 * Agenstorm itself changes, so the title-bar button and the status-bar widget ([BackgroundTerminalsAction],
 * [BackgroundTerminalsWidgetFactory]) show *N background terminals* only while there are any. *Open* and *Stop* act
 * on one ([open], [stop]). Each read also cleans up (U3.4): a background terminal whose program has ended is stopped,
 * and the run's first read stops what an earlier run left behind. Nobody's busy process is ever ended on a timer.
 */
@Service(Service.Level.APP)
class BackgroundTerminals(private val scope: CoroutineScope) {

    private val listed = MutableStateFlow<List<TmuxSession>>(emptyList())
    private val started = AtomicBoolean()
    private val swept = AtomicBoolean()
    private val reading = Mutex()

    /** The background terminals as last read, oldest first. */
    val sessions: StateFlow<List<TmuxSession>> get() = listed

    /** Starts reading every [POLL]; called as projects open, once a run. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            while (isActive) {
                read()
                delay(POLL)
            }
        }
    }

    /** Reads tmux again now — after a keep, a stop, an attach. */
    fun refresh() {
        scope.launch { read() }
    }

    fun stop(sessions: List<TmuxSession>) {
        scope.launch {
            withContext(Dispatchers.IO) { sessions.forEach { Tmux.getInstance().run(*TmuxBackgroundPlan.stop(it.name).toTypedArray()) } }
            LOG.info("Agenstorm: stopped background terminals ${sessions.joinToString { it.name }}")
            read()
        }
    }

    /**
     * A tab for [session]: in [project]'s window when it is a worktree of the same repository — or when the folder it
     * came from is gone — where it then belongs ([TmuxBackgroundPlan.adopt]); otherwise in its own project's window,
     * opened if need be, where U3.1 gives it its tab. A tab of the window already asking for it is selected instead.
     */
    fun open(project: Project, session: TmuxSession) {
        scope.launch {
            val base = project.basePath
            val repository = withContext(Dispatchers.IO) { base?.let { GitCommonDir.of(Path.of(it))?.toString() } }
            val folder = session.project?.takeIf { withContext(Dispatchers.IO) { Files.isDirectory(Path.of(it)) } }
            when (TmuxBackgroundPlan.openIn(session, repository, folder != null)) {
                TmuxBackgroundPlan.OpenIn.THIS_WINDOW -> {
                    if (base != null) withContext(Dispatchers.IO) { Tmux.getInstance().run(*TmuxBackgroundPlan.adopt(session.name, base).toTypedArray()) }
                    withContext(Dispatchers.EDT) { showIn(project, session) }
                }
                TmuxBackgroundPlan.OpenIn.ITS_PROJECT -> {
                    val path = Path.of(folder ?: return@launch)
                    val open = withContext(Dispatchers.EDT) { ProjectManager.getInstance().openProjects.firstOrNull { it.basePath == folder } }
                    if (open != null) withContext(Dispatchers.EDT) {
                        showIn(open, session)
                        ProjectUtil.focusProjectWindow(open, true)
                    } else {
                        @Suppress("DEPRECATION") // the inline builder needs JVM 25 (OpenRequestServer has the story)
                        ProjectUtil.openOrImportAsync(path, OpenProjectTask.build().withForceOpenInNewFrame(true))
                    }
                }
            }
            read()
        }
    }

    private fun showIn(project: Project, session: TmuxSession) {
        if (project.isDisposed) return
        val existing = TmuxTabs.of(project, create = true).firstOrNull { it.second == session.name }?.first
        if (existing != null) existing.content.manager?.setSelectedContent(existing.content)
        else TmuxTabs.openAttached(project, session.name, session.tabName ?: session.label, rename = null, focus = true)
        TmuxTabs.showToolWindow(project, focus = true)
    }

    private suspend fun read() = reading.withLock {
        val on = AgenstormSettings.getInstance().state.terminalTmuxEnabled && !SystemInfo.isWindows
        val now = if (!on) emptyList() else withContext(Dispatchers.IO) {
            val tmux = Tmux.getInstance()
            if (tmux.binary() == null) return@withContext emptyList()
            val sessions = TmuxSessions.read(tmux)
            val gone = TmuxBackgroundPlan.ended(sessions) +
                if (swept.compareAndSet(false, true)) TmuxBackgroundPlan.leftovers(sessions, ManagementFactory.getRuntimeMXBean().startTime) else emptyList()
            gone.forEach { tmux.run(*TmuxBackgroundPlan.stop(it.name).toTypedArray()) }
            if (gone.isNotEmpty()) LOG.info("Agenstorm: cleaned up tmux sessions ${gone.joinToString { it.name }}")
            TmuxBackgroundPlan.listed(sessions - gone.toSet())
        }
        if (now == listed.value) return@withLock
        val countChanged = now.size != listed.value.size
        listed.value = now
        withContext(Dispatchers.EDT) { showCount(availabilityChanged = countChanged) }
    }

    /** The button updates with the next toolbar refresh; each window's widget is added, removed or redrawn. */
    private fun showCount(availabilityChanged: Boolean) {
        ActivityTracker.getInstance().inc()
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed || project.isDefault) continue
            if (availabilityChanged) project.getService(StatusBarWidgetsManager::class.java)?.updateWidget(BackgroundTerminalsWidgetFactory::class.java)
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(BackgroundTerminalsWidgetFactory.ID)
        }
    }

    companion object {
        private val LOG = logger<BackgroundTerminals>()
        private val POLL = 10.seconds

        fun getInstance(): BackgroundTerminals = service()
    }
}
