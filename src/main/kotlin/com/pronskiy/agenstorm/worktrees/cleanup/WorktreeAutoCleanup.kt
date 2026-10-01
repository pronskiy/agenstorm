package com.pronskiy.agenstorm.worktrees.cleanup

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.worktrees.WorktreeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Step T5.3 (decision 94). With `worktreesAutoCleanup` on, once a day per repository — whichever of its windows gets
 * there first — the worktrees with nothing to lose and no use for `worktreesAutoCleanupDays` days are removed, and a
 * balloon names them. Never one with changes, unmerged commits, a live agent's lock or a running terminal, nor one a
 * window shows ([StaleWorktrees]).
 */
@Service(Service.Level.PROJECT)
class WorktreeAutoCleanup(private val project: Project, private val scope: CoroutineScope) {

    fun start() {
        scope.launch {
            delay(FIRST)
            while (isActive) {
                sweep()
                delay(CHECK)
            }
        }
    }

    private suspend fun sweep() {
        val settings = AgenstormSettings.getInstance().state
        if (!settings.worktreesEnabled || !settings.worktreesAutoCleanup || project.isDisposed) return
        val repository = WorktreeRegistry.getInstance(project).state.value.commonDir ?: return
        val now = System.currentTimeMillis()
        val last = lastRun[repository]
        if (last != null && now - last < DAY_MS) return
        lastRun[repository] = now
        val candidates = CleanUpWorktreesFlow.gather(project) ?: return
        val stale = StaleWorktrees.stale(candidates, now, settings.worktreesAutoCleanupDays)
        if (stale.isEmpty()) return
        val (removed, failed) = CleanUpWorktreesFlow.remove(project, stale)
        LOG.info("Agenstorm: automatic cleanup removed ${removed.joinToString()} (${failed.size} refused)")
        val done = removed.takeIf { it.isNotEmpty() }?.let {
            AgenstormBundle.message("worktrees.cleanup.auto.done", it.size, it.joinToString(", "), settings.worktreesAutoCleanupDays)
        }
        CleanUpWorktreesFlow.report(project, done, failed)
    }

    companion object {
        private val LOG = logger<WorktreeAutoCleanup>()
        private val FIRST = 2.minutes
        private val CHECK = 1.hours
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Per repository (its common git dir): when a window last swept it, so two windows of one repository sweep once. */
        private val lastRun = ConcurrentHashMap<String, Long>()

        fun getInstance(project: Project): WorktreeAutoCleanup = project.service()
    }
}
