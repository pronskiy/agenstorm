package com.pronskiy.agenstorm.worktrees

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationType
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
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.ProjectFrameHelper
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormNotifications
import com.pronskiy.agenstorm.core.busy.ProjectBusyGuard
import com.pronskiy.agenstorm.worktrees.carry.IdeaSeeder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFrame

/**
 * Step T1.6. Carries out [SwitchPolicy]'s plan. The target is opened first — with `forceOpenInNewFrame`, because
 * `withProjectToClose` defers to the user's *Open project in* choice and opens a second window under *New window*
 * (T1.4) — then given the old frame's bounds and focused, and only then is the old worktree closed, so there is a
 * window at every moment (Epic P's `loadThenClose`). A close re-asks the guards: one that is refused at the last moment
 * is remembered as left behind rather than forced. Every side effect goes through [Env], keyed by path.
 */
class SwitchRunner(private val env: Env) {

    interface Env {
        /** Which of [paths] have an open project. */
        fun loaded(paths: Set<String>): Set<String>
        fun isBusy(path: String): Boolean
        fun exists(path: String): Boolean
        suspend fun open(path: String): Boolean
        /** Gives [target]'s window the bounds of [like]'s and brings it to the front. */
        fun present(target: String, like: String)
        /** Closes the project at [path] unless a guard objects now; false when it stays open. */
        fun close(path: String): Boolean
        fun notify(message: String)
    }

    var leftBehind: Set<String> = emptySet()
        private set

    /** Switches the window showing [current] to [target], one of [worktrees]; false when nothing happened. */
    suspend fun switch(current: String, target: String, worktrees: Set<String>): Boolean {
        val loaded = env.loaded(worktrees + current)
        val busy = loaded.filter(env::isBusy).toSet()
        val plan = SwitchPolicy.decide(target, current, loaded, busy, leftBehind) ?: return false
        if (plan.open) {
            if (!env.exists(target)) {
                env.notify(AgenstormBundle.message("worktrees.switch.gone", target))
                return false
            }
            if (!env.open(target)) {
                env.notify(AgenstormBundle.message("worktrees.switch.failed", target))
                return false
            }
        }
        env.present(target, like = current)
        val remembered = plan.leftBehind.toMutableSet()
        if (plan.closeCurrent && !env.close(current)) remembered += current
        for (path in plan.closeLeftBehind) if (!env.close(path)) remembered += path
        leftBehind = remembered
        return true
    }
}

/** Step T1.6. The application's one [SwitchRunner]: it outlives the project a switch closes, and so does its scope. */
@Service(Service.Level.APP)
class WorktreeSwitcher(private val scope: CoroutineScope) {

    private val runner = SwitchRunner(PlatformEnv())
    private val mutex = Mutex()

    /** [onArrived] gets the target's project once the window shows it (T2.5 runs the setup there). */
    fun switch(from: Project, target: String, onArrived: (Project) -> Unit = {}) {
        val current = from.basePath?.let(FileUtil::toSystemIndependentName) ?: return
        LOG.debug(Throwable("switch requested")) { "switch $current -> $target" }
        val snapshot = WorktreeRegistry.getInstance(from).state.value
        val worktrees = snapshot.worktrees.map { it.path }.toSet()
        val main = snapshot.worktrees.firstOrNull { it.isMain }?.path
        scope.launch(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            if (!mutex.tryLock()) return@launch
            try {
                withContext(Dispatchers.IO) { seedIdea(target, listOfNotNull(main, current)) }
                if (runner.switch(current, target, worktrees)) {
                    ProjectManager.getInstance().openProjects
                        .firstOrNull { !it.isDisposed && it.basePath?.let(FileUtil::toSystemIndependentName) == target }
                        ?.let(onArrived)
                }
            } finally {
                mutex.unlock()
            }
        }
    }

    /**
     * Step T2.4, lazily: a worktree made outside the IDE (an agent, the Git tool window) gets the main checkout's
     * `.idea` — or, if the main checkout has none, the current worktree's — before its first open.
     */
    private fun seedIdea(target: String, sources: List<String>) {
        val idea = Path.of(target, ".idea")
        if (Files.exists(idea) || !Files.isDirectory(Path.of(target))) return
        val source = sources.filter { it != target }.map { Path.of(it, ".idea") }.firstOrNull(Files::isDirectory) ?: return
        IdeaSeeder.seed(source, idea)
    }

    private class PlatformEnv : SwitchRunner.Env {

        private fun project(path: String): Project? =
            ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed && it.basePath?.let(FileUtil::toSystemIndependentName) == path }

        override fun loaded(paths: Set<String>): Set<String> =
            ProjectManager.getInstance().openProjects.mapNotNull { it.basePath?.let(FileUtil::toSystemIndependentName) }.filter { it in paths }.toSet()

        override fun isBusy(path: String): Boolean = project(path)?.let { ProjectBusyGuard.busyReason(it) != null } ?: false

        override fun exists(path: String): Boolean = Files.isDirectory(Path.of(path))

        override suspend fun open(path: String): Boolean =
            ProjectUtil.openOrImportAsync(Path.of(path), OpenProjectTask.build().withForceOpenInNewFrame(true)) != null

        override fun present(target: String, like: String) {
            val targetProject = project(target) ?: return
            project(like)?.let { mirrorBounds(it, targetProject) }
            ProjectUtil.focusProjectWindow(targetProject, true)
        }

        override fun close(path: String): Boolean {
            val project = project(path) ?: return true
            val reason = ProjectBusyGuard.busyReason(project)
            if (reason != null) {
                LOG.info("Leaving ${project.name} open behind the switch: $reason")
                return false
            }
            return ProjectManager.getInstance().closeAndDispose(project)
        }

        override fun notify(message: String) {
            AgenstormNotifications.group()
                .createNotification(AgenstormBundle.message("worktrees.notice.title"), message, NotificationType.WARNING)
                .notify(null)
        }

        private fun mirrorBounds(from: Project, target: Project) {
            val windowManager = WindowManager.getInstance()
            val source = windowManager.getFrame(from) ?: return
            val destination = windowManager.getFrame(target) ?: return
            if (source === destination || isFullScreen(source) || isFullScreen(destination)) return
            if (destination.bounds != source.bounds) destination.bounds = source.bounds
        }

        private fun isFullScreen(frame: JFrame): Boolean = try {
            ProjectFrameHelper.getFrameHelper(frame)?.isInFullScreen == true
        } catch (e: LinkageError) {
            LOG.warn("Cannot tell whether the frame is in full screen; not mirroring bounds", e)
            true
        }
    }

    companion object {
        private val LOG = logger<WorktreeSwitcher>()

        fun getInstance(): WorktreeSwitcher = service()
    }
}
