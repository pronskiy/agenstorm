package com.pronskiy.agenstorm.tabs

import com.intellij.ide.DataManager
import com.intellij.ide.RecentProjectListActionProvider
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.ProjectFrameHelper
import com.intellij.ui.awt.RelativePoint
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.tabs.offload.ProjectOffloadService
import com.pronskiy.agenstorm.tabs.ui.ProjectTabsPanel
import java.awt.Component
import java.awt.Point
import java.awt.datatransfer.StringSelection
import javax.swing.JFrame

/**
 * Steps E1.4/P2.7. What the tabs do: left click switches to the project's window (mirroring the window bounds
 * first when both windows are ordinary, so the other project appears "in place"), middle click or × closes it
 * (after switching to a neighbour when it is the frame's own project, so focus does not fall to the desktop),
 * "+" opens the recent-projects popup with the stock widget's actions, right click offers Close / Close Others /
 * Offload Project / Copy Path. On an offloaded bookmark a click loads the project, × or middle click forgets it,
 * and the menu offers Load / Forget / Copy Path.
 * Everything here runs on the EDT (mouse events); the close itself is posted with `invokeLater` because it tears
 * down the very frame whose toolbar dispatched the click.
 */
object ProjectTabActions {

    private val LOG = logger<ProjectTabActions>()

    /** Connects a strip to the platform. */
    fun wire(panel: ProjectTabsPanel, model: ProjectTabsModel = ProjectTabsModel.getInstance()) {
        panel.onSelect = { tab ->
            when (tab) {
                is ProjectTab.Loaded -> switchTo(tab.project, from = panel.ownerProject)
                is ProjectTab.Offloaded -> load(tab, panel)
            }
        }
        panel.onClose = { tab ->
            when (tab) {
                is ProjectTab.Loaded -> close(tab.project, owner = panel.ownerProject, tabs = model.loadedProjects())
                is ProjectTab.Offloaded -> model.forget(tab.key)
            }
        }
        panel.onAdd = { anchor -> showAddPopup(anchor) }
        panel.onContextMenu = { tab, component, point -> showContextMenu(tab, component, point, owner = panel.ownerProject, tabs = model.loadedProjects()) }
        panel.onReorder = { tab, index -> model.moveTab(tab.key, index) }
    }

    /** A click on a bookmark (P2.7): the tab shows it is loading, the service opens the project. */
    fun load(tab: ProjectTab.Offloaded, panel: ProjectTabsPanel? = null) {
        panel?.tabLabels()?.firstOrNull { it.tab == tab }?.isLoading = true
        ProjectOffloadService.getInstance().load(tab)
    }

    /** Offload Project from the context menu (P2.7); the guards still apply and say why when they object. */
    fun offload(target: Project) {
        ProjectOffloadService.getInstance().offloadNow(target)
    }

    fun switchTo(target: Project, from: Project?) {
        if (target.isDisposed) return
        if (from != null && from !== target && shouldMirrorBounds(from, target)) mirrorWindowBounds(from, target)
        ProjectUtil.focusProjectWindow(target, true)
    }

    /**
     * Only worth doing when the two projects really are two windows. Inside a macOS tab group they are one
     * window already, so mirroring would move the whole group onto itself.
     */
    private fun shouldMirrorBounds(from: Project, target: Project): Boolean =
        AgenstormSettings.getInstance().state.tabsMirrorWindowBounds &&
            !NativeTabStrip.isTabbed(from) &&
            !NativeTabStrip.isTabbed(target)

    fun close(target: Project, owner: Project?, tabs: List<Project>) {
        if (target.isDisposed) return
        if (target === owner) neighbourOf(target, tabs)?.let { switchTo(it, from = target) }
        ApplicationManager.getApplication().invokeLater({
            if (!target.isDisposed) ProjectManager.getInstance().closeAndDispose(target)
        }, { target.isDisposed })
    }

    fun showAddPopup(anchor: Component) {
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, addPopupGroup(), DataManager.getInstance().getDataContext(anchor), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
            .showUnderneathOf(anchor)
    }

    fun showContextMenu(tab: ProjectTab, component: Component, point: Point, owner: Project?, tabs: List<Project>) {
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, contextMenuGroup(tab, owner, tabs), DataManager.getInstance().getDataContext(component), JBPopupFactory.ActionSelectionAid.MNEMONICS, true)
            .show(RelativePoint(component, point))
    }

    /** The tab that takes over when [target] closes: the one after it, else the one before it, else none. */
    fun neighbourOf(target: Project, tabs: List<Project>): Project? {
        val others = tabs.filter { it !== target && !it.isDisposed }
        if (others.isEmpty()) return null
        val index = tabs.indexOf(target)
        if (index < 0) return others.first()
        return tabs.drop(index + 1).firstOrNull { it in others } ?: tabs.take(index).lastOrNull { it in others }
    }

    /**
     * Recent projects (flat, no "clear list" entry), then the stock widget's own actions. The boolean overloads of
     * `getActions` are the public ones; `getActions(Project)` is `@ApiStatus.Internal`.
     */
    fun addPopupGroup(): DefaultActionGroup {
        val group = DefaultActionGroup()
        group.addAll(RecentProjectListActionProvider.getInstance().getActions(addClearListItem = false, useGroups = false))
        (ActionManager.getInstance().getAction(STOCK_ACTIONS_GROUP) as? ActionGroup)?.let {
            group.addSeparator()
            group.add(it)
        }
        return group
    }

    fun contextMenuGroup(tab: ProjectTab, owner: Project?, tabs: List<Project>): DefaultActionGroup = when (tab) {
        is ProjectTab.Loaded -> DefaultActionGroup(
            action(AgenstormBundle.message("tabs.menu.close")) { close(tab.project, owner, tabs) },
            action(AgenstormBundle.message("tabs.menu.closeOthers")) {
                for (other in tabs) if (other !== tab.project) close(other, owner, tabs)
            },
            action(AgenstormBundle.message("tabs.menu.offload")) { offload(tab.project) },
            action(AgenstormBundle.message("tabs.menu.copyPath")) { copyPath(tab.project) },
        )
        is ProjectTab.Offloaded -> DefaultActionGroup(
            action(AgenstormBundle.message("tabs.menu.load")) { load(tab) },
            action(AgenstormBundle.message("tabs.menu.forget")) { ProjectTabsModel.getInstance().forget(tab.key) },
            action(AgenstormBundle.message("tabs.menu.copyPath")) { copyPath(tab.key) },
        )
    }

    fun copyPath(target: Project) {
        copyPath(target.basePath ?: return)
    }

    fun copyPath(path: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(path))
    }

    private fun mirrorWindowBounds(from: Project, target: Project) {
        val windowManager = WindowManager.getInstance()
        val source = windowManager.getFrame(from) ?: return
        val destination = windowManager.getFrame(target) ?: return
        if (source === destination || isFullScreen(source) || isFullScreen(destination)) return
        if (destination.bounds != source.bounds) destination.bounds = source.bounds
    }

    private fun isFullScreen(frame: JFrame): Boolean = try {
        ProjectFrameHelper.getFrameHelper(frame)?.isInFullScreen == true
    } catch (e: LinkageError) {
        LOG.warn("Cannot tell whether the frame is in full screen; not mirroring bounds", e)
        true
    }

    private fun action(text: String, perform: () -> Unit): DumbAwareAction = object : DumbAwareAction(text) {
        override fun actionPerformed(e: AnActionEvent) = perform()
    }

    /** The group the stock project widget appends to its popup (New Project, Open, Clone…). */
    const val STOCK_ACTIONS_GROUP = "ProjectWidget.Actions"
}
