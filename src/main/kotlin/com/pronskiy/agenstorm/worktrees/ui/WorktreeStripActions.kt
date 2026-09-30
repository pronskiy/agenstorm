package com.pronskiy.agenstorm.worktrees.ui

import com.intellij.ide.DataManager
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.awt.RelativePoint
import com.pronskiy.agenstorm.core.AgenstormAppScope
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.worktrees.WorktreeRegistry
import com.pronskiy.agenstorm.worktrees.WorktreeTab
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalText
import com.pronskiy.agenstorm.worktrees.cleanup.RemoveWorktreeFlow
import com.pronskiy.agenstorm.worktrees.merge.MergeBackFlow
import com.pronskiy.agenstorm.worktrees.setup.SetupConfig
import com.pronskiy.agenstorm.worktrees.setup.SetupRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

/**
 * Step T1.5. The right-click menu of a worktree tab: Open in New Window, Copy Path, and Open in Terminal — the last one
 * the Terminal plugin's own `Terminal.OpenInTerminal` run on the worktree's folder in *this* project, so an agent can
 * be started there without leaving the window, and absent when the Terminal plugin is — and Run Setup Script (T2.5) for a
 * worktree made elsewhere, when the repository has a setup; and (T4.1–T4.3) Merge Back, Archive Worktree and Remove
 * Worktree on every tab but the main checkout's.
 */
object WorktreeStripActions {

    private val LOG = logger<WorktreeStripActions>()
    private const val OPEN_IN_TERMINAL = "Terminal.OpenInTerminal"
    private const val PLACE = "Agenstorm.WorktreeStrip"

    fun contextMenuGroup(project: Project, tab: WorktreeTab): DefaultActionGroup {
        val actions = mutableListOf<AnAction>()
        if (!tab.isCurrent) actions += action(AgenstormBundle.message("worktrees.menu.openInNewWindow")) { openInNewWindow(tab.path) }
        actions += action(AgenstormBundle.message("worktrees.menu.copyPath")) { copyPath(tab.path) }
        if (ActionManager.getInstance().getAction(OPEN_IN_TERMINAL) != null) {
            actions += action(AgenstormBundle.message("worktrees.menu.openInTerminal")) { openInTerminal(project, tab.path) }
        }
        val main = WorktreeRegistry.getInstance(project).state.value.worktrees.firstOrNull { it.isMain }?.path
        if (main != null && !tab.worktree.isMain && SetupConfig.find(Path.of(main)) != null) {
            actions += action(AgenstormBundle.message("worktrees.menu.runSetup")) { SetupRunner.run(project, Path.of(main), Path.of(tab.path)) }
        }
        if (!tab.worktree.isMain) {
            actions += Separator.getInstance()
            actions += action(AgenstormBundle.message("worktrees.menu.merge")) { MergeBackFlow.start(project, tab.worktree) }
            actions += action(AgenstormBundle.message("worktrees.menu.archive")) { RemoveWorktreeFlow.start(project, tab.worktree, RemovalText.Mode.ARCHIVE) }
            actions += action(AgenstormBundle.message("worktrees.menu.remove")) { RemoveWorktreeFlow.start(project, tab.worktree) }
        }
        return DefaultActionGroup(actions)
    }

    fun showContextMenu(project: Project, tab: WorktreeTab, component: Component, point: Point) {
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, contextMenuGroup(project, tab), DataManager.getInstance().getDataContext(component), JBPopupFactory.ActionSelectionAid.MNEMONICS, true)
            .show(RelativePoint(component, point))
    }

    fun copyPath(path: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(FileUtil.toSystemDependentName(path)))
    }

    /** Focuses the worktree's window when it is open already; otherwise opens it in a frame of its own. */
    fun openInNewWindow(path: String) {
        val open = ProjectManager.getInstance().openProjects.firstOrNull { it.basePath?.let(FileUtil::toSystemIndependentName) == path }
        if (open != null) {
            ProjectUtil.focusProjectWindow(open, true)
            return
        }
        service<AgenstormAppScope>().scope.launch(Dispatchers.EDT) {
            try {
                ProjectUtil.openOrImportAsync(Path.of(path), OpenProjectTask.build().withForceOpenInNewFrame(true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Agenstorm: could not open worktree $path", e)
            }
        }
    }

    /** The folder is looked up off the EDT — a VFS refresh there is a slow operation — and the action run back on it. */
    private fun openInTerminal(project: Project, path: String) {
        val action = ActionManager.getInstance().getAction(OPEN_IN_TERMINAL) ?: return
        service<AgenstormAppScope>().scope.launch {
            val dir = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path)) } ?: return@launch
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                val context = SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, project)
                    .add(CommonDataKeys.VIRTUAL_FILE, dir)
                    .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(dir))
                    .build()
                ActionUtil.performAction(action, AnActionEvent.createEvent(context, action.templatePresentation.clone(), PLACE, ActionUiKind.NONE, null))
            }
        }
    }

    private fun action(text: String, run: () -> Unit): AnAction = object : DumbAwareAction(text) {
        override fun actionPerformed(e: AnActionEvent) = run()
    }
}
