package com.pronskiy.agenstorm.worktrees.cleanup

import com.intellij.CommonBundle
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.pronskiy.agenstorm.core.AgenstormAppScope
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormNotifications
import com.pronskiy.agenstorm.core.busy.ProjectBusyGuard
import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.WorktreeRegistry
import com.pronskiy.agenstorm.worktrees.WorktreeSwitcher
import com.pronskiy.agenstorm.worktrees.status.LockOwner
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Steps T4.1–T4.2. A tab's *Remove Worktree…* and *Archive Worktree…*: read the facts in the background, refuse with the
 * reason when [RemovalPlan] says so, otherwise ask — the dialog names what would be lost, and its button reads
 * *Remove Anyway* when anything would — then close the worktree's project and remove (or archive) it. Removing the worktree the window shows switches the window
 * to the main checkout first (T1.6), and the rest runs from there. A project that will not close — it became busy
 * meanwhile — stops the removal. Runs in the application scope, since the switch closes the project that started it.
 */
object RemoveWorktreeFlow {

    private val COMMANDS = mapOf(
        "status" to GitCommand.STATUS,
        "rev-list" to GitCommand.REV_LIST,
        "config" to GitCommand.CONFIG,
        "rev-parse" to GitCommand.REV_PARSE,
        "worktree" to GitCommand.WORKTREE,
        "branch" to GitCommand.BRANCH,
        "add" to GitCommand.ADD,
        "commit" to GitCommand.COMMIT,
    )
    private val WRITES = setOf("worktree", "branch", "add", "commit")

    fun start(project: Project, worktree: Worktree, mode: RemovalText.Mode = RemovalText.Mode.REMOVE) {
        val main = WorktreeRegistry.getInstance(project).state.value.worktrees.firstOrNull { it.isMain } ?: return
        val name = worktree.path.substringAfterLast('/')
        val scope = service<AgenstormAppScope>().scope
        scope.launch {
            val busy = withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) { open(worktree.path)?.let { ProjectBusyGuard.busyReason(it) } }
            val lock = if (worktree.isLocked) RemovalPlan.LockState.valueOf(LockOwner.current(worktree.lockReason).name) else RemovalPlan.LockState.NONE
            val facts = withBackgroundProgress(project, RemovalText.checking(mode, name)) {
                withContext(Dispatchers.IO) { remover(project).facts(main, worktree, lock, busy) }
            }
            val plan = if (mode == RemovalText.Mode.ARCHIVE) RemovalPlan.archive(facts) else RemovalPlan.plan(facts)
            if (plan !is RemovalPlan.Plan.Ready) {
                RemovalText.refused(name, plan, mode)?.let { notify(project, it, NotificationType.WARNING) }
                return@launch
            }
            val confirmed = withContext(Dispatchers.EDT) {
                MessageDialogBuilder.okCancel(RemovalText.title(mode), RemovalText.question(name, FileUtil.toSystemDependentName(worktree.path), plan, mode))
                    .yesText(RemovalText.confirmButton(plan, mode))
                    .noText(CommonBundle.getCancelButtonText())
                    .icon(Messages.getWarningIcon())
                    .ask(project)
            }
            if (!confirmed) return@launch
            val current = project.basePath?.let(FileUtil::toSystemIndependentName) == worktree.path
            if (!current) {
                closeThenRemove(project, main, worktree, plan, name, mode)
                return@launch
            }
            withContext(Dispatchers.EDT) {
                WorktreeSwitcher.getInstance().switch(project, main.path) { arrived ->
                    scope.launch { closeThenRemove(arrived, main, worktree, plan, name, mode) }
                }
            }
        }
    }

    private suspend fun closeThenRemove(project: Project, main: Worktree, worktree: Worktree, plan: RemovalPlan.Plan.Ready, name: String, mode: RemovalText.Mode) {
        val closed = withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            val open = open(worktree.path) ?: return@withContext true
            ProjectBusyGuard.busyReason(open) == null && ProjectManager.getInstance().closeAndDispose(open)
        }
        if (!closed) {
            notify(project, RemovalText.notClosed(mode, name), NotificationType.WARNING)
            return
        }
        val outcome = withBackgroundProgress(project, RemovalText.progress(mode, name)) {
            withContext(Dispatchers.IO) { remover(project).remove(Path.of(main.path), worktree, plan) }
        }
        val type = if (outcome is WorktreeRemover.Outcome.Removed) NotificationType.INFORMATION else NotificationType.WARNING
        notify(project, RemovalText.outcome(name, outcome, mode), type)
        WorktreeRegistry.getInstance(project).refresh()
    }

    private fun open(path: String): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed && it.basePath?.let(FileUtil::toSystemIndependentName) == path }

    /** Through git4idea's public `GitLineHandler`; the reads stay out of the Git console, the removal shows there. */
    private fun remover(project: Project) = WorktreeRemover { dir, args ->
        val handler = GitLineHandler(project, dir, COMMANDS.getValue(args.first()))
        handler.setSilent(args.first() !in WRITES)
        handler.addParameters(args.drop(1))
        val result = Git.getInstance().runCommand(handler)
        WorktreeRemover.GitResult(result.success(), result.output, result.errorOutputAsJoinedString)
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        AgenstormNotifications.group()
            .createNotification(AgenstormBundle.message("worktrees.notice.title"), message, type)
            .notify(project.takeUnless { it.isDisposed })
    }
}
