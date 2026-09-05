package com.pronskiy.agenstorm.commit

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.ui.CommitMessage
import com.pronskiy.agenstorm.commit.llm.LlmBackends
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Toolbar button above the commit message field (`Vcs.MessageActionGroup`, both the tool window and the
 * dialog). One click starts a generation, a second click while it runs cancels it. The included changes are
 * read in [actionPerformed] because the commit UI's tree state is EDT-only.
 */
class GenerateCommitMessageAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val state = AgenstormSettings.getInstance().state
        val hasCommitUi = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) is CommitMessage
        val visible = project != null && state.commitEnabled && hasCommitUi
        e.presentation.isVisible = visible
        if (!visible || project == null) {
            e.presentation.isEnabled = false
            return
        }
        val running = project.service<CommitGenerationService>().isRunning
        val configured = LlmBackends.forId(state.commitBackendId) != null
        e.presentation.isEnabled = running || configured
        e.presentation.icon = if (running) AllIcons.Run.Stop else AllIcons.Actions.Lightning
        e.presentation.text = AgenstormBundle.message(if (running) "commit.action.stop" else "action.Agenstorm.GenerateCommitMessage.text")
        e.presentation.description = AgenstormBundle.message(if (configured) "action.Agenstorm.GenerateCommitMessage.description" else "commit.action.noBackend")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.service<CommitGenerationService>()
        if (service.isRunning) {
            service.cancel()
            return
        }
        val ui = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) ?: return
        val message = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) as? CommitMessage ?: return
        val backend = LlmBackends.forId(AgenstormSettings.getInstance().state.commitBackendId) ?: return
        val changes = ui.getIncludedChanges()
        val unversioned = ui.getIncludedUnversionedFiles()
        if (changes.isEmpty() && unversioned.isEmpty()) {
            NotificationGroupManager.getInstance().getNotificationGroup(CommitGenerationService.NOTIFICATION_GROUP)
                .createNotification(AgenstormBundle.message("commit.notification.nothingIncluded"), NotificationType.INFORMATION)
                .notify(project)
            return
        }
        service.generate(message, changes, unversioned, backend)
    }
}
