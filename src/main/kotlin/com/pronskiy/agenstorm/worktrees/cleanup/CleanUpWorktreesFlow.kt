package com.pronskiy.agenstorm.worktrees.cleanup

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.pronskiy.agenstorm.core.AgenstormAppScope
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormNotifications
import com.pronskiy.agenstorm.core.busy.FolderProcesses
import com.pronskiy.agenstorm.core.busy.ProjectBusyGuard
import com.pronskiy.agenstorm.worktrees.WorktreeRegistry
import com.pronskiy.agenstorm.worktrees.carry.Preparations
import com.pronskiy.agenstorm.worktrees.cleanup.StaleWorktrees.Candidate
import com.pronskiy.agenstorm.worktrees.status.LockOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Step T5.3 (decision 94). *Clean Up Worktrees…*: the facts of every linked worktree of the repository, read in the
 * background ([gather]); a dialog listing the ones whose removal loses nothing, all ticked, and the rest with what keeps
 * each ([CleanUpWorktreesDialog]); then T4.1's removal, without `--force`, of the ticked ones — so a worktree that
 * changed in between is refused by git rather than lost. A worktree a window shows is never offered. The automatic
 * cleanup ([WorktreeAutoCleanup]) goes through [gather] and [remove] too.
 */
object CleanUpWorktreesFlow {

    fun start(project: Project) {
        service<AgenstormAppScope>().scope.launch {
            val candidates = withBackgroundProgress(project, AgenstormBundle.message("worktrees.cleanup.checking")) { gather(project) } ?: return@launch
            val split = StaleWorktrees.split(candidates)
            val chosen = withContext(Dispatchers.EDT) { CleanUpWorktreesDialog(project, split).takeIf { it.showAndGet() }?.chosen } ?: return@launch
            val picked = split.removable.filter { it.name in chosen }
            if (picked.isEmpty()) return@launch
            val (removed, failed) = withBackgroundProgress(project, AgenstormBundle.message("worktrees.cleanup.progress")) { remove(project, picked) }
            report(project, AgenstormBundle.message("worktrees.cleanup.done", removed.size, removed.joinToString(", ")), failed)
        }
    }

    /** Every linked worktree of [project]'s repository, with what removing it would mean and when it was last used. */
    internal suspend fun gather(project: Project): List<Candidate>? {
        val snapshot = WorktreeRegistry.getInstance(project).state.value
        val main = snapshot.worktrees.firstOrNull { it.isMain } ?: return null
        val open = withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault }
                .associate { (it.basePath?.let(FileUtil::toSystemIndependentName) ?: "") to ProjectBusyGuard.busyReason(it) }
        }
        val remover = RemoveWorktreeFlow.remover(project)
        return withContext(Dispatchers.IO) {
            snapshot.worktrees.filter { !it.isMain }.map { worktree ->
                val lock = if (worktree.isLocked) RemovalPlan.LockState.valueOf(LockOwner.current(worktree.lockReason).name) else RemovalPlan.LockState.NONE
                val facts = remover.facts(main, worktree, lock, open[worktree.path], FolderProcesses.running(Path.of(worktree.path)))
                val reflog = snapshot.commonDir?.let { common -> worktree.adminId?.let { modified(Path.of(common, "worktrees", it, "logs", "HEAD")) } }
                val saved = modified(Path.of(worktree.path, ".idea", "workspace.xml"))
                Candidate(worktree, RemovalPlan.plan(facts), StaleWorktrees.lastActive(worktree.createdAt, reflog, saved), open = worktree.path in open)
            }
        }
    }

    /** Removes [chosen] — the names removed, and a line for each that git refused. */
    internal suspend fun remove(project: Project, chosen: List<Candidate>): Pair<List<String>, List<String>> {
        val main = WorktreeRegistry.getInstance(project).state.value.worktrees.firstOrNull { it.isMain } ?: return emptyList<String>() to emptyList()
        val remover = RemoveWorktreeFlow.remover(project)
        val outcomes = withContext(Dispatchers.IO) {
            chosen.map { candidate ->
                val ready = candidate.plan as RemovalPlan.Plan.Ready
                FolderProcesses.end(Path.of(candidate.worktree.path))
                candidate to remover.remove(Path.of(main.path), candidate.worktree, ready)
            }
        }
        val removed = outcomes.filter { it.second is WorktreeRemover.Outcome.Removed }.map { it.first }
        removed.forEach { Preparations.forget(it.worktree.path) }
        WorktreeRegistry.getInstance(project).refresh()
        val failed = outcomes.mapNotNull { (candidate, outcome) -> (outcome as? WorktreeRemover.Outcome.Failed)?.let { RemovalText.outcome(candidate.name, it) } }
        return removed.map { it.name } to failed
    }

    internal fun report(project: Project, done: String?, failed: List<String>) {
        val text = listOfNotNull(done, *failed.toTypedArray()).joinToString("\n")
        if (text.isEmpty()) return
        AgenstormNotifications.group()
            .createNotification(AgenstormBundle.message("worktrees.notice.title"), text, if (failed.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
            .notify(project.takeUnless { it.isDisposed })
    }

    private fun modified(path: Path): Long? = try {
        Files.getLastModifiedTime(path).toMillis()
    } catch (_: IOException) {
        null
    }
}
