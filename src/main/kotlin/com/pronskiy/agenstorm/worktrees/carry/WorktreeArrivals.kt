package com.pronskiy.agenstorm.worktrees.carry

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.worktrees.WorktreeRegistry
import com.pronskiy.agenstorm.worktrees.WorktreeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Step T4.8. With `worktreesPrepareOnAppear` on, a worktree that appears while this project is open — `git worktree
 * add` in a terminal, `claude -w` — is prepared at once ([WorktreePreparer.prepareMadeElsewhere]), so an agent working
 * there has `.env` and `vendor/` without the worktree ever being opened here. [Arrivals] decides what counts as having
 * just appeared; a short wait lets `git worktree add` finish its checkout first. Every open project of the repository
 * sees the arrival, and [Preparations] makes sure only one of them prepares it.
 */
@Service(Service.Level.PROJECT)
class WorktreeArrivals(private val project: Project, private val scope: CoroutineScope) {

    private val started = AtomicBoolean()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var previous = WorktreeSnapshot.EMPTY
            WorktreeRegistry.getInstance(project).state.collect { current ->
                val arrived = Arrivals.between(previous, current, System.currentTimeMillis())
                previous = current
                val main = current.worktrees.firstOrNull { it.isMain }?.path ?: return@collect
                if (!AgenstormSettings.getInstance().state.worktreesPrepareOnAppear) return@collect
                arrived.forEach { worktree -> scope.launch { prepare(main, worktree.path) } }
            }
        }
    }

    private suspend fun prepare(main: String, path: String) {
        delay(SETTLE)
        if (!Files.exists(Path.of(path, ".git"))) return
        Preparations.once(path) {
            withBackgroundProgress(project, AgenstormBundle.message("worktrees.prepare.progress", path.substringAfterLast('/'))) {
                withContext(Dispatchers.IO) { WorktreePreparer.prepareMadeElsewhere(project, Path.of(main), Path.of(path)) }
            }
        }
    }

    companion object {
        private val SETTLE = 2.seconds

        fun getInstance(project: Project): WorktreeArrivals = project.service()
    }
}
