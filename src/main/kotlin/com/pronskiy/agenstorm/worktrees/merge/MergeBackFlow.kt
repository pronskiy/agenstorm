package com.pronskiy.agenstorm.worktrees.merge

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.pronskiy.agenstorm.core.AgenstormAppScope
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormNotifications
import com.pronskiy.agenstorm.core.busy.ProjectBusyGuard
import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.WorktreeRegistry
import com.pronskiy.agenstorm.worktrees.WorktreeSwitcher
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan
import com.pronskiy.agenstorm.worktrees.cleanup.RemoveWorktreeFlow
import com.pronskiy.agenstorm.worktrees.cleanup.WorktreeRemover
import com.pronskiy.agenstorm.worktrees.status.LockOwner
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Step T4.3. A tab's *Merge Back…*: read the facts, refuse with the reason when [MergePlan] says so, otherwise ask how
 * ([MergeBackDialog]) and run it ([MergeRunner]). Where the window goes next follows what needs doing: after a squash,
 * to the base's worktree with the Commit tool window open, since the staged result waits for its message; after a
 * conflict, to the worktree that holds it; after a rebase, nowhere. Success offers *Remove Worktree*. Runs in the
 * application scope, since a switch closes the project that started it.
 */
object MergeBackFlow {

    private val COMMANDS = mapOf(
        "status" to GitCommand.STATUS,
        "rev-list" to GitCommand.REV_LIST,
        "config" to GitCommand.CONFIG,
        "rev-parse" to GitCommand.REV_PARSE,
        "diff" to GitCommand.DIFF,
        "add" to GitCommand.ADD,
        "commit" to GitCommand.COMMIT,
        "merge" to GitCommand.MERGE,
        "rebase" to GitCommand.REBASE,
        "reset" to GitCommand.RESET,
    )
    private val WRITES = setOf("add", "commit", "merge", "rebase", "reset")

    fun start(project: Project, worktree: Worktree) {
        val worktrees = WorktreeRegistry.getInstance(project).state.value.worktrees
        val main = worktrees.firstOrNull { it.isMain } ?: return
        val name = worktree.path.substringAfterLast('/')
        val scope = service<AgenstormAppScope>().scope
        scope.launch {
            val busy = withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) { open(worktree.path)?.let { ProjectBusyGuard.busyReason(it) } }
            val lock = if (worktree.isLocked) RemovalPlan.LockState.valueOf(LockOwner.current(worktree.lockReason).name) else RemovalPlan.LockState.NONE
            val runner = runner(project)
            val facts = withBackgroundProgress(project, AgenstormBundle.message("worktrees.merge.checking", name)) {
                withContext(Dispatchers.IO) { runner.facts(main, worktree, worktrees, lock, busy) }
            }
            val plan = MergePlan.plan(facts)
            if (plan !is MergePlan.Plan.Ready) {
                MergeText.refused(name, plan)?.let { notify(project, it, NotificationType.WARNING, null) }
                return@launch
            }
            val strategy = withContext(Dispatchers.EDT) { MergeBackDialog(project, name, plan).takeIf { it.showAndGet() }?.strategy } ?: return@launch
            val outcome = withBackgroundProgress(project, AgenstormBundle.message("worktrees.merge.progress", name, plan.base)) {
                withContext(Dispatchers.IO) { runner.run(Path.of(worktree.path), plan, strategy, name) }
            }
            LocalFileSystem.getInstance().refreshNioFiles(listOf(Path.of(worktree.path), Path.of(plan.baseWorktree)), true, true, null)
            val message = MergeText.outcome(name, plan, FileUtil.toSystemDependentName(plan.baseWorktree), outcome)
            val current = project.basePath?.let(FileUtil::toSystemIndependentName)
            when {
                outcome is MergeRunner.Outcome.Merged && strategy == MergePlan.Strategy.SQUASH ->
                    arrive(project, current, plan.baseWorktree) { there ->
                        VcsDirtyScopeManager.getInstance(there).markEverythingDirty()
                        ToolWindowManager.getInstance(there).getToolWindow(ToolWindowId.COMMIT)?.activate(null)
                        notify(there, message, NotificationType.INFORMATION, worktree)
                    }
                outcome is MergeRunner.Outcome.Merged -> notify(project, message, NotificationType.INFORMATION, worktree)
                outcome is MergeRunner.Outcome.Conflict ->
                    arrive(project, current, worktree.path) { there ->
                        VcsDirtyScopeManager.getInstance(there).markEverythingDirty()
                        notify(there, message, NotificationType.WARNING, null)
                    }
                else -> notify(project, message, NotificationType.WARNING, null)
            }
        }
    }

    /** Runs [then] in the project at [path] — this window's, or the one the switcher brings this window to. */
    private suspend fun arrive(project: Project, current: String?, path: String, then: (Project) -> Unit) {
        withContext(Dispatchers.EDT) {
            if (current == path || project.isDisposed) then(project) else WorktreeSwitcher.getInstance().switch(project, path, then)
        }
    }

    private fun open(path: String): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed && it.basePath?.let(FileUtil::toSystemIndependentName) == path }

    /** Through git4idea's public `GitLineHandler`; the reads stay out of the Git console, the merge shows there. */
    private fun runner(project: Project) = MergeRunner { dir, args ->
        val handler = GitLineHandler(project, dir, COMMANDS.getValue(args.first()))
        handler.setSilent(args.first() !in WRITES)
        handler.addParameters(args.drop(1))
        val result = Git.getInstance().runCommand(handler)
        WorktreeRemover.GitResult(result.success(), result.output, result.errorOutputAsJoinedString)
    }

    /** [removable] adds *Remove Worktree* for it. */
    private fun notify(project: Project, message: String, type: NotificationType, removable: Worktree?) {
        val notification = AgenstormNotifications.group().createNotification(AgenstormBundle.message("worktrees.notice.title"), message, type)
        if (removable != null) {
            notification.addAction(NotificationAction.createSimpleExpiring(AgenstormBundle.message("worktrees.merge.remove")) { RemoveWorktreeFlow.start(project, removable) })
        }
        notification.notify(project.takeUnless { it.isDisposed })
    }
}
