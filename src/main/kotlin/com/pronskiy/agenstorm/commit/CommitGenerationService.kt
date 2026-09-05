package com.pronskiy.agenstorm.commit

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.pronskiy.agenstorm.commit.llm.LlmBackend
import com.pronskiy.agenstorm.commit.llm.LlmException
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormConfigurable
import com.pronskiy.agenstorm.core.AgenstormSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs one commit-message generation at a time: saves the typed hint to the message history, clears the
 * field, collects the diff in a read action, streams the backend's deltas into the field on the EDT and
 * post-processes the result. Every document change uses the same command group id, so Undo reverts the
 * whole generation in one step. Failures restore the hint and show a balloon with an "Open Settings" link.
 */
@Service(Service.Level.PROJECT)
class CommitGenerationService(private val project: Project, private val scope: CoroutineScope) {

    private val current = AtomicReference<Job?>(null)

    val isRunning: Boolean
        get() = current.get()?.isActive == true

    fun cancel() {
        current.get()?.cancel()
    }

    /** Returns the running job; a second call while one is active returns that job instead of starting another. */
    fun generate(message: CommitMessage, changes: List<Change>, unversioned: List<FilePath>, backend: LlmBackend): Job {
        current.get()?.takeIf { it.isActive }?.let { return it }
        val state = AgenstormSettings.getInstance().state
        val document = message.editorField.document
        val hint = document.text.trim()
        val groupId = Any()
        val job = scope.launch {
            try {
                withBackgroundProgress(project, AgenstormBundle.message("commit.progress.title"), true) {
                    if (hint.isNotEmpty()) {
                        withContext(Dispatchers.EDT) { VcsConfiguration.getInstance(project).saveCommitMessage(hint) }
                    }
                    replaceText(document, "", groupId)
                    val collected = readAction { DiffCollector(project, state.commitMaxDiffChars).collect(changes, unversioned) }
                    val context = PromptContext(
                        diff = collected.diff,
                        stat = collected.stat,
                        branch = "",
                        hint = hint,
                        language = state.commitLanguage,
                        conventionalCommits = state.commitConventionalCommits,
                    )
                    val request = promptBuilder(state).build(context, state.commitModel)
                    backend.stream(request).collect { chunk -> appendText(document, chunk, groupId) }
                    val processed = MessagePostProcessor.process(readAction { document.text }, state.commitBodyEnabled)
                    replaceText(document, processed, groupId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is LlmException) LOG.warn("Commit message generation failed", e)
                replaceText(document, hint, groupId)
                notifyFailure(e)
            }
        }
        current.set(job)
        job.invokeOnCompletion { current.compareAndSet(job, null) }
        return job
    }

    private fun promptBuilder(state: AgenstormSettings.State): PromptBuilder = PromptBuilder(
        systemTemplate = state.commitSystemPrompt.ifBlank { PromptBuilder.DEFAULT_SYSTEM },
        userTemplate = state.commitUserPrompt.ifBlank { PromptBuilder.DEFAULT_USER },
    )

    private suspend fun appendText(document: Document, chunk: String, groupId: Any) = withContext(Dispatchers.EDT) {
        command(groupId) { document.insertString(document.textLength, chunk) }
    }

    private suspend fun replaceText(document: Document, text: String, groupId: Any) = withContext(Dispatchers.EDT) {
        command(groupId) { if (document.text != text) document.setText(text) }
    }

    private fun command(groupId: Any, action: () -> Unit) {
        CommandProcessor.getInstance().executeCommand(
            project,
            { ApplicationManager.getApplication().runWriteAction(action) },
            AgenstormBundle.message("commit.command.name"),
            groupId,
        )
    }

    private suspend fun notifyFailure(e: Exception) = withContext(Dispatchers.EDT) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(AgenstormBundle.message("commit.notification.failed.title"), e.message ?: e.javaClass.simpleName, NotificationType.ERROR)
            .addAction(NotificationAction.createSimple(AgenstormBundle.message("commit.notification.openSettings")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, AgenstormConfigurable::class.java)
            })
            .notify(project)
    }

    companion object {
        const val NOTIFICATION_GROUP = "Agenstorm"
        private val LOG = logger<CommitGenerationService>()
    }
}
