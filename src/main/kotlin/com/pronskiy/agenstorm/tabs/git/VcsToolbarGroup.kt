package com.pronskiy.agenstorm.tabs.git

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step E3.1. Replaces the Git plugin's `MainToolbarVCSGroup` (branch widget, merge/rebase widget, "create
 * repository") in the main toolbar. While project tabs are on and the branch lives in the status bar, the group
 * hides; otherwise it behaves exactly like the stock group. Should the platform ever drop the children other
 * plugins attached to the replaced group, [getChildren] resolves the stock ones by id.
 */
class VcsToolbarGroup : DefaultActionGroup() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = !BranchStatusBarWidgetFactory.isFeatureOn()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val own = super.getChildren(e)
        if (own.isNotEmpty()) return own
        val manager = ActionManager.getInstance()
        return STOCK_CHILDREN.mapNotNull(manager::getAction).toTypedArray()
    }

    companion object {
        const val ID = "MainToolbarVCSGroup"
        val STOCK_CHILDREN: List<String> = listOf("main.toolbar.git.Branches", "main.toolbar.git.MergeRebase", "Vcs.ToolbarWidget.CreateRepository")
    }
}
