package com.pronskiy.agenstorm.projectview

import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFileSystemItem
import com.intellij.refactoring.copy.CopyFilesOrDirectoriesHandler
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormNotifications

/**
 * Step O2.2. Copies one file or folder next to itself under a new name, in one command so Undo is one step.
 *
 * `CopyFilesOrDirectoriesHandler.copyToDirectory` is the platform's own copy — the same one the Copy File
 * dialog ends in — so a folder is copied with everything in it. Its overwrite prompt is reachable only when
 * the target name is taken, which the field has already refused.
 */
object InlineDuplicate {

    /** One group id, so a copied folder and its contents undo together. */
    const val COMMAND_GROUP: String = "agenstorm.projectview.duplicate"

    private val log = logger<InlineDuplicate>()

    /**
     * @return the first file copied, which is `null` for a folder that had none — the copy still happened.
     *         A failure is already on screen as a balloon.
     */
    fun duplicate(project: Project, source: PsiFileSystemItem, newName: String): PsiFileSystemItem? {
        val parent = source.parent as? PsiDirectory ?: return null
        var created: PsiFileSystemItem? = null
        var failure: Exception? = null

        WriteCommandAction.runWriteCommandAction(
            project,
            AgenstormBundle.message("projectview.command.duplicate", source.name),
            COMMAND_GROUP,
            Runnable {
                try {
                    CopyFilesOrDirectoriesHandler.copyToDirectory(source, newName, parent)
                    created = parent.findSubdirectory(newName) ?: parent.findFile(newName)
                } catch (e: Exception) {
                    failure = e
                }
            },
        )

        failure?.let {
            log.info("Inline duplicate of '${source.name}' failed", it)
            AgenstormNotifications.group()
                .createNotification(
                    AgenstormBundle.message("projectview.duplicate.failed.title"),
                    it.message ?: AgenstormBundle.message("projectview.create.failed.unknown"),
                    NotificationType.ERROR,
                )
                .notify(project)
        }
        return created
    }
}
