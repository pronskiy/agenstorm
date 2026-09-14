package com.pronskiy.agenstorm.projectview

import com.intellij.ide.actions.CreateFileAction
import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.fileTemplates.FileTemplate
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.pronskiy.agenstorm.core.AgenstormSettings
import javax.swing.SwingUtilities

/**
 * Step O3.2. One of the New menu's typed entries — *PHP File*, *HTML File*, *JavaScript File* — with its
 * dialog replaced by the row in the tree. The file it creates is the one the dialog would have created,
 * because it comes from the same [FileTemplate].
 *
 * **The template is found by the entry's own name**, decision 59. Every one of these extends
 * `CreateFileFromTemplateAction`, whose `buildDialog` is `protected`, so there is no way to ask an entry which
 * template it uses; what there is, is the convention that the entry and its template share a name, and
 * `FileTemplateManager` will answer to it. An entry whose name resolves to nothing keeps its dialog, so the
 * guess costs nothing when it is wrong.
 */
class InlineTemplateFileAction(
    private val templateName: String,
    private val delegate: AnAction?,
) : AnAction() {

    private val log = logger<InlineTemplateFileAction>()

    init {
        delegate?.let { templatePresentation.copyFrom(it.templatePresentation, null, true) }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = delegate?.actionUpdateThread ?: ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        if (delegate == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        delegate.update(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        if (project == null || !AgenstormSettings.getInstance().state.projectTreeInlineNamingEnabled) {
            return fallBack(e)
        }
        if (templateOf(project) == null) return fallBack(e)

        val tree = ProjectTreeAccess.tree(project) ?: return fallBack(e)
        val context = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        val fromTree = context === tree || (context != null && SwingUtilities.isDescendingFrom(context, tree))
        if (!fromTree) return fallBack(e)

        val target = InlineTarget.resolve(tree, fromTree = true) ?: return fallBack(e)
        val siblings = target.directory.children.mapTo(HashSet()) { child -> child.name }

        val opened = InlineNameEditor.open(
            tree = tree,
            anchor = target.anchor,
            placement = target.placement,
            kind = InlineNameKind.NEW_FILE,
            initialText = "",
            isDirectory = false,
            siblingNames = siblings,
        ) { typed -> commit(project, target.directory, typed) }

        if (opened == null) fallBack(e)
    }

    private fun templateOf(project: Project): FileTemplate? {
        val manager = FileTemplateManager.getInstance(project)
        return manager.findInternalTemplate(templateName)
            ?: manager.allTemplates.firstOrNull { it.name == templateName }
    }

    private fun commit(project: Project, directory: VirtualFile, typed: String) {
        val template = templateOf(project) ?: return
        var created: PsiFile? = null
        WriteCommandAction.runWriteCommandAction(
            project,
            templatePresentation.text ?: templateName,
            InlineCreate.COMMAND_GROUP,
            Runnable {
                val psiDirectory = PsiManager.getInstance(project).findDirectory(directory) ?: return@Runnable
                try {
                    val mkdirs = CreateFileAction.MkDirs(withExtension(typed, template), psiDirectory)
                    created = CreateFileFromTemplateAction.createFileFromTemplate(
                        mkdirs.newName, template, mkdirs.directory, null, true,
                    )
                } catch (e: Exception) {
                    log.info("Inline create of '$typed' from template '$templateName' failed", e)
                }
            },
        )
        val file = created?.virtualFile ?: return
        ApplicationManager.getApplication().invokeLater({
            ProjectTreeAccess.reveal(project, created, file)
        }, project.disposed)
    }

    /** The dialog adds the template's extension when you leave it off; so does the row. */
    private fun withExtension(typed: String, template: FileTemplate): String {
        val extension = template.extension
        if (extension.isEmpty() || FileUtilRt.getExtension(typed).isNotEmpty()) return typed
        return "$typed.$extension"
    }

    private fun fallBack(e: AnActionEvent) {
        delegate?.let { ActionUtil.performAction(it, e) }
    }
}
