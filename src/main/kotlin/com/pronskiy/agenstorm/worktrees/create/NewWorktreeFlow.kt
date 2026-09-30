package com.pronskiy.agenstorm.worktrees.create

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.pronskiy.agenstorm.core.AgenstormAppScope
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormNotifications
import com.pronskiy.agenstorm.worktrees.WorktreeRegistry
import com.pronskiy.agenstorm.worktrees.WorktreeSwitcher
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Step T2.1 (minimal). The strip's "+": ask for a name and a base, create the worktree in the background, then switch
 * the window to it (T1.6). Carrying `.env` and `vendor/` over, seeding `.idea` and the setup script are T2.2–T2.5.
 * Runs in the application scope, because the switch at the end closes the project that started it.
 */
object NewWorktreeFlow {

    fun start(project: Project) {
        val repository = GitRepositoryManager.getInstance(project).repositories.singleOrNull() ?: return
        val creator = WorktreeCreator(project, repository)
        val taken = WorktreeRegistry.getInstance(project).state.value.worktrees.map { it.path.substringAfterLast('/') }.toSet()
        service<AgenstormAppScope>().scope.launch {
            val defaultBranch = withContext(Dispatchers.IO) { creator.defaultBranch() }
            val dialog = withContext(Dispatchers.EDT) {
                NewWorktreeDialog(project, repository.currentBranchName, defaultBranch, taken).takeIf { it.showAndGet() }
            } ?: return@launch
            val slug = dialog.slug ?: return@launch
            val defaultBase = defaultBranch?.takeIf { dialog.fromDefaultBranch }
            val result = withBackgroundProgress(project, AgenstormBundle.message("worktrees.new.progress", slug)) {
                withContext(Dispatchers.IO) {
                    if (defaultBase != null) creator.create(slug, "origin/HEAD", defaultBase)
                    else creator.create(slug, "HEAD", repository.currentBranchName ?: repository.currentRevision ?: "HEAD")
                }
            }
            when (result) {
                is WorktreeCreator.Result.Created -> withContext(Dispatchers.EDT) { WorktreeSwitcher.getInstance().switch(project, result.path) }
                is WorktreeCreator.Result.Failed -> AgenstormNotifications.group()
                    .createNotification(AgenstormBundle.message("worktrees.notice.title"), AgenstormBundle.message("worktrees.new.failed", slug, result.reason), NotificationType.WARNING)
                    .notify(project)
            }
        }
    }
}
