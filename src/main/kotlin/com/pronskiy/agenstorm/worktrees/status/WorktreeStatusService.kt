package com.pronskiy.agenstorm.worktrees.status

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.IdeFrame
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import com.pronskiy.agenstorm.core.busy.ProjectBusyGuard
import com.pronskiy.agenstorm.worktrees.WorktreeRegistry
import com.pronskiy.agenstorm.worktrees.WorktreeSnapshot
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Step T3.2. The tabs' status: `git status` per worktree path, why a worktree loaded in the IDE is busy, and which
 * locked worktrees were locked by a process that has ended since ([LockOwner]).
 */
data class WorktreeStatuses(val git: Map<String, WorktreeStatus>, val busy: Map<String, String>, val endedLocks: Set<String> = emptySet()) {

    companion object {
        val EMPTY = WorktreeStatuses(emptyMap(), emptyMap())
    }
}

/**
 * Step T3.2. Keeps [state] current for the worktrees [WorktreeRegistry] lists, without a timer that spawns git while
 * nothing happens. A run is asked for by a repository change (refs moved: every tab), a file event
 * ([StatusTriggers]: inside a worktree, or under its admin dir), a new snapshot and the IDE coming to the front, and
 * one worker runs them, [StatusThrottle] keeping each worktree to one run every 3 s. Runs happen only while the strip
 * is showing ([setShowing]), the feature is on and there are at least two worktrees; requests made meanwhile wait.
 *
 * A worktree nobody opened in this IDE has no files in the VFS, so an agent editing it produces no event until it
 * touches git. Claude Code locks the worktree its session works in, recording the session's pid, so a tick every 5 s
 * — only while the IDE is in front — asks for the locked worktrees not loaded here as well: every tick while the
 * locking process runs, never once it has ended (Claude Code leaves the lock behind), and as often as [PollBackoff]
 * allows for a lock that names no process (5 s while it keeps changing, down to once a minute); the same tick
 * re-reads the busy guards
 * ([ProjectBusyGuard], on the EDT like the switcher does) of the worktrees that are loaded. `git status` is git4idea's
 * `readOptional` command: it runs with `GIT_OPTIONAL_LOCKS=0` and never writes an agent's index.
 */
@Service(Service.Level.PROJECT)
class WorktreeStatusService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    private val mutableState = MutableStateFlow(WorktreeStatuses.EMPTY)
    val state: StateFlow<WorktreeStatuses> = mutableState.asStateFlow()

    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val throttle = StatusThrottle(THROTTLE.inWholeMilliseconds)
    private val backoff = PollBackoff(TICK.inWholeMilliseconds, POLL_MAX.inWholeMilliseconds)
    private val reader = StatusReader(::runGit)
    private val started = AtomicBoolean()

    @Volatile
    private var snapshot = WorktreeSnapshot.EMPTY

    @Volatile
    private var showing = false

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val bus = project.messageBus.connect(this)
        bus.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener {
            reader.forgetBases()
            requestAll()
        })
        bus.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) = request(StatusTriggers.affected(events.map { it.path }, snapshot))
        })
        val appBus = ApplicationManager.getApplication().messageBus.connect(this)
        appBus.subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
            override fun applicationActivated(ideFrame: IdeFrame) = requestAll()
        })
        appBus.subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { requestAll() })
        scope.launch {
            WorktreeRegistry.getInstance(project).state.collect { next ->
                snapshot = next
                reader.forgetBases()
                if (next.worktrees.size < 2) mutableState.value = WorktreeStatuses.EMPTY else requestAll()
            }
        }
        scope.launch {
            while (true) {
                wake.receive()
                delay(DEBOUNCE)
                drain()
            }
        }
        scope.launch {
            while (true) {
                delay(TICK)
                if (active() && ApplicationManager.getApplication().isActive) tick()
            }
        }
    }

    /** The strip reports whether any of its panels is on screen; turning visible catches up on what was asked meanwhile. */
    fun setShowing(value: Boolean) {
        showing = value
        if (value) wake.trySend(Unit)
    }

    private fun requestAll() = request(snapshot.worktrees.map { it.path })

    private fun request(paths: Collection<String>) {
        if (paths.isEmpty()) return
        pending += paths
        wake.trySend(Unit)
    }

    private fun active(): Boolean = showing && snapshot.worktrees.size >= 2 && AgenstormSettings.getInstance().state.worktreesEnabled

    private suspend fun drain() {
        while (active()) {
            val known = snapshot.worktrees.map { it.path }.toSet()
            pending.retainAll(known)
            throttle.retain(known)
            backoff.retain(known)
            val now = System.currentTimeMillis()
            for (path in throttle.due(pending, now)) {
                pending.remove(path)
                throttle.ran(path, now)
                val status = withContext(Dispatchers.IO) { reader.read(Path.of(path)) }
                backoff.observed(path, changed = status != state.value.git[path], now = now)
                mutableState.update { current ->
                    val git = if (status != null) current.git + (path to status) else current.git - path
                    current.copy(git = git.filterKeys { it in known })
                }
            }
            refreshBusy()
            val wait = throttle.wait(pending, System.currentTimeMillis()) ?: return
            delay(wait)
        }
    }

    private suspend fun tick() {
        val loaded = loadedProjects().keys
        val locked = snapshot.worktrees.filter { it.isLocked && it.path !in loaded }.groupBy({ lockState(it.lockReason) }, { it.path })
        request(locked[LockOwner.State.LIVE].orEmpty() + backoff.due(locked[LockOwner.State.UNKNOWN].orEmpty(), System.currentTimeMillis()))
        refreshBusy()
    }

    private suspend fun refreshBusy() {
        val worktrees = snapshot.worktrees
        val busy = withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            val loaded = loadedProjects()
            worktrees.mapNotNull { worktree -> loaded[worktree.path]?.let { ProjectBusyGuard.busyReason(it) }?.let { worktree.path to it } }.toMap()
        }
        val ended = worktrees.filter { it.isLocked && lockState(it.lockReason) == LockOwner.State.ENDED }.map { it.path }.toSet()
        mutableState.update { it.copy(busy = busy, endedLocks = ended) }
    }

    private fun lockState(reason: String?): LockOwner.State =
        LockOwner.state(
            reason,
            started = { pid -> ProcessHandle.of(pid).flatMap { it.info().startInstant() }.orElse(null) },
            alive = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
        )

    private fun loadedProjects(): Map<String, Project> =
        ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed }
            .mapNotNull { open -> open.basePath?.let { FileUtil.toSystemIndependentName(it) to open } }
            .toMap()

    /** Through git4idea's public `GitLineHandler`, silent: nothing of this reaches the Git console. */
    private fun runGit(dir: Path, command: StatusReader.Command, args: List<String>): List<String>? {
        val handler = GitLineHandler(project, dir, when (command) {
            StatusReader.Command.STATUS -> GitCommand.STATUS
            StatusReader.Command.REV_LIST -> GitCommand.REV_LIST
            StatusReader.Command.CONFIG -> GitCommand.CONFIG
            StatusReader.Command.REV_PARSE -> GitCommand.REV_PARSE
        })
        handler.setSilent(true)
        handler.setStdoutSuppressed(true)
        handler.addParameters(args)
        val result = Git.getInstance().runCommand(handler)
        LOG.debug { "git ${command.name.lowercase()} in $dir: ${if (result.success()) "ok" else "failed (${result.exitCode})"}" }
        return if (result.success()) result.output else null
    }

    override fun dispose() = Unit

    companion object {
        private val LOG = logger<WorktreeStatusService>()
        private val DEBOUNCE = 300.milliseconds
        private val THROTTLE = 3.seconds
        private val TICK = 5.seconds
        private val POLL_MAX = 60.seconds

        fun getInstance(project: Project): WorktreeStatusService = project.service()
    }
}
