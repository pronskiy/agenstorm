package com.pronskiy.agenstorm.worktrees.create

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.pronskiy.agenstorm.core.AgenstormAppScope
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormNotifications
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.worktrees.WorktreeRegistry
import com.pronskiy.agenstorm.worktrees.WorktreeSwitcher
import com.pronskiy.agenstorm.worktrees.carry.Preparations
import com.pronskiy.agenstorm.worktrees.carry.WorktreePreparer
import com.pronskiy.agenstorm.worktrees.setup.SetupRunner
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Step T2.1 (minimal). The strip's "+": ask for a name and a base, create the worktree in the background, then switch
 * the window to it (T1.6). In between, [WorktreePreparer] carries over what the new worktree needs (T2.2–T2.4); once
 * the window shows it, the repository's setup runs there (T2.5). T4.2: the worktree can also take an existing branch.
 * Runs in the application scope, because the switch at the end closes the project that started it.
 */
object NewWorktreeFlow {

    fun start(project: Project) {
        val repository = GitRepositoryManager.getInstance(project).repositories.singleOrNull() ?: return
        val creator = WorktreeCreator(project, repository)
        val taken = WorktreeRegistry.getInstance(project).state.value.worktrees.map { it.path.substringAfterLast('/') }.toSet()
        service<AgenstormAppScope>().scope.launch {
            val (defaultBranch, freeBranches) = withContext(Dispatchers.IO) { creator.defaultBranch() to creator.freeBranches() }
            val dialog = withContext(Dispatchers.EDT) {
                NewWorktreeDialog(project, repository.currentBranchName, defaultBranch, freeBranches, taken).takeIf { it.showAndGet() }
            } ?: return@launch
            val slug = dialog.slug ?: return@launch
            // T4.8: the new worktree is this flow's to prepare; the watcher that prepares arrivals waits for it.
            val target = creator.targetFor(slug)
            Preparations.forget(target)
            var created: WorktreeCreator.Result = WorktreeCreator.Result.Failed("")
            Preparations.once(target) {
                created = withBackgroundProgress(project, AgenstormBundle.message("worktrees.new.progress", slug)) {
                    withContext(Dispatchers.IO) {
                        val existing = dialog.branch
                        when {
                            dialog.base == NewWorktreeDialog.Base.EXISTING_BRANCH && existing != null -> creator.createFromBranch(slug, existing)
                            dialog.base == NewWorktreeDialog.Base.DEFAULT_BRANCH && defaultBranch != null -> creator.create(slug, "origin/HEAD", defaultBranch)
                            else -> creator.create(slug, "HEAD", repository.currentBranchName ?: repository.currentRevision ?: "HEAD")
                        }
                    }
                }
                (created as? WorktreeCreator.Result.Created)?.let { made ->
                    withBackgroundProgress(project, AgenstormBundle.message("worktrees.new.preparing", slug)) {
                        withContext(Dispatchers.IO) { WorktreePreparer.prepare(project, Path.of(made.main), Path.of(made.path)) }
                    }
                }
            }
            when (val result = created) {
                is WorktreeCreator.Result.Created -> {
                    val runSetup = AgenstormSettings.getInstance().state.worktreesRunSetup
                    withContext(Dispatchers.EDT) {
                        WorktreeSwitcher.getInstance().switch(project, result.path) { arrived ->
                            if (runSetup) SetupRunner.run(arrived, Path.of(result.main), Path.of(result.path))
                        }
                    }
                }
                is WorktreeCreator.Result.Failed -> {
                    Preparations.forget(target)
                    AgenstormNotifications.group()
                        .createNotification(AgenstormBundle.message("worktrees.notice.title"), AgenstormBundle.message("worktrees.new.failed", slug, result.reason), NotificationType.WARNING)
                        .notify(project)
                }
            }
        }
    }
}
