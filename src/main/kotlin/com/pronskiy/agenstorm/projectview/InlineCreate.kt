package com.pronskiy.agenstorm.projectview

import com.intellij.ide.actions.CreateFileAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormNotifications

/**
 * Step O1.3. Turns what was typed into a file or a folder, in one command so Undo is one step.
 *
 * `CreateFileAction.MkDirs` is the platform's own splitter — the same one the dialog uses — so `src/Http/Client.php`
 * makes the two folders here exactly as it does there. It writes, which is why it is constructed inside the write
 * action and not before it.
 */
object InlineCreate {

    /** One group id, so the folders and the file a single commit made undo together. */
    const val COMMAND_GROUP: String = "agenstorm.projectview.create"

    private val log = logger<InlineCreate>()

    /**
     * @param asDirectory the caller's kind; a trailing separator in [typed] asks for a folder as well, which is
     *                    how `foo/` gets one out of the New File field.
     * @return what was created, or `null` if it could not be — the reason is already on screen as a balloon.
     */
    fun create(project: Project, targetDir: VirtualFile, typed: String, asDirectory: Boolean): PsiFileSystemItem? {
        val path = InlineNamePolicy.split(typed)
        val wantDirectory = asDirectory || path.trailingSeparator
        val commandName = AgenstormBundle.message(
            if (wantDirectory) "projectview.command.newDirectory" else "projectview.command.newFile",
            path.name,
        )

        var created: PsiFileSystemItem? = null
        var failure: Exception? = null
        WriteCommandAction.runWriteCommandAction(project, commandName, COMMAND_GROUP, Runnable {
            val directory = PsiManager.getInstance(project).findDirectory(targetDir) ?: return@Runnable
            try {
                created = if (wantDirectory) makeDirectories(directory, path) else makeFile(directory, typed)
            } catch (e: Exception) {
                failure = e
            }
        })

        failure?.let {
            log.info("Inline create of '$typed' failed", it)
            AgenstormNotifications.group()
                .createNotification(
                    AgenstormBundle.message("projectview.create.failed.title"),
                    it.message ?: AgenstormBundle.message("projectview.create.failed.unknown"),
                    NotificationType.ERROR,
                )
                .notify(project)
        }
        return created
    }

    private fun makeDirectories(parent: PsiDirectory, path: TypedPath): PsiDirectory {
        var directory = parent
        for (segment in path.parents + path.name) {
            directory = CreateFileAction.findOrCreateSubdirectory(directory, segment)
        }
        return directory
    }

    /**
     * Empty, which is what the stock action does: `CreateFileAction.create` is `directory.createFile(name)` and
     * nothing more. Applying the extension's file template was tried and dropped — typing `Foo.php` into the
     * IDE's own New | File gives an empty file too, and the inline row is meant to change where you type the
     * name, not what you get. The `<?php` still comes from PhpStorm's *PHP File* entry, which is untouched.
     */
    private fun makeFile(parent: PsiDirectory, typed: String): PsiFileSystemItem {
        val mkdirs = CreateFileAction.MkDirs(typed, parent)
        return mkdirs.directory.createFile(mkdirs.newName)
    }
}
