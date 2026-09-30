package com.pronskiy.agenstorm.worktrees

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.IdeFrame
import com.pronskiy.agenstorm.worktrees.carry.WorktreeArrivals
import com.pronskiy.agenstorm.worktrees.status.WorktreeStatusService
import com.pronskiy.agenstorm.worktrees.ui.WorktreeStripService
import git4idea.commands.Git
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Step T1.2. The worktrees of this project's repository, kept current. git4idea's public `Git.listWorktrees` gives
 * the list and [WorktreeAdminDirs] the lock reasons and creation times; the result is [state].
 *
 * A worktree an agent adds from any terminal shows up without a click: `<common git dir>/worktrees/` is put under
 * the file watcher, and a VFS event there, a repository change, a change of VCS mappings or the IDE coming back to
 * the front all ask for a refresh, debounced. A project with no Git root or with several repositories has no single
 * answer to "which worktrees", so its state stays empty and the strip hides.
 */
@Service(Service.Level.PROJECT)
class WorktreeRegistry(private val project: Project, private val scope: CoroutineScope) : Disposable {

    private val mutableState = MutableStateFlow(WorktreeSnapshot.EMPTY)
    val state: StateFlow<WorktreeSnapshot> = mutableState.asStateFlow()

    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val started = AtomicBoolean()

    @Volatile
    private var watchedDir: String? = null
    private var watch: LocalFileSystem.WatchRequest? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val bus = project.messageBus.connect(this)
        bus.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { refresh() })
        bus.subscribe(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED, VcsRepositoryMappingListener { refresh() })
        bus.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val dir = watchedDir ?: return
                if (events.any { changesList(dir, it.path) }) refresh()
            }
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
            override fun applicationActivated(ideFrame: IdeFrame) = refresh()
        })
        scope.launch {
            requests.collectLatest {
                delay(DEBOUNCE)
                load()
            }
        }
        refresh()
    }

    fun refresh() {
        requests.tryEmit(Unit)
    }

    private suspend fun load() {
        val repository = GitRepositoryManager.getInstance(project).repositories.singleOrNull()
        if (repository == null) {
            mutableState.value = WorktreeSnapshot.EMPTY
            return
        }
        val worktreesDir = repository.repositoryFiles.worktreesDirFile.toPath()
        val snapshot = withContext(Dispatchers.IO) {
            val trees = try {
                Git.getInstance().listWorktrees(repository)
            } catch (e: VcsException) {
                LOG.debug("git worktree list failed for ${repository.root.path}", e)
                return@withContext null
            }
            WorktreeSnapshots.build(
                commonDir = worktreesDir.parent.toString(),
                trees = trees.map { WorktreeSnapshots.GitTree(it.path.path, it.currentBranch?.name, it.isMain, it.isLocked, it.isPrunable) },
                admin = WorktreeAdminDirs.read(worktreesDir),
            )
        } ?: return
        watch(worktreesDir)
        mutableState.value = snapshot
    }

    /**
     * Puts the admin dirs under the file watcher and loads them and their files into the VFS — the VFS reports a change
     * only for a file it has loaded — so a new admin dir, a `locked` file coming or going and (T3.2) a worktree's index
     * or HEAD changing each produce an event.
     */
    private fun watch(worktreesDir: Path) {
        val path = FileUtil.toSystemIndependentName(worktreesDir.toString())
        if (path != watchedDir) {
            watch?.let(LocalFileSystem.getInstance()::removeWatchedRoot)
            watch = LocalFileSystem.getInstance().addRootToWatch(path, true)
            watchedDir = path
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(worktreesDir)
        }
        LocalFileSystem.getInstance().findFileByNioFile(worktreesDir)?.children?.forEach { it.children }
    }

    override fun dispose() {
        watch?.let(LocalFileSystem.getInstance()::removeWatchedRoot)
        watch = null
    }

    companion object {
        private val LOG = logger<WorktreeRegistry>()
        private val DEBOUNCE = 300.milliseconds
        private val LIST_FILES = setOf("gitdir", "locked", "HEAD")

        /** An admin dir coming or going, or its `gitdir`, `locked` or `HEAD` changing; not an index write or a reflog line. */
        internal fun changesList(worktreesDir: String, path: String): Boolean {
            if (!path.startsWith("$worktreesDir/")) return false
            val parts = path.removePrefix("$worktreesDir/").split('/')
            return parts.size == 1 || parts.size == 2 && parts[1] in LIST_FILES
        }

        fun getInstance(project: Project): WorktreeRegistry = project.service()
    }
}

/** Step T1.2: starts the registry when a project opens, (T1.4) the strip and (T3.2) the tabs' status. Registered in `agenstorm-git.xml`. */
class WorktreeStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        WorktreeRegistry.getInstance(project).start()
        WorktreeStatusService.getInstance(project).start()
        WorktreeArrivals.getInstance(project).start()
        WorktreeStripService.getInstance(project).start()
    }
}
