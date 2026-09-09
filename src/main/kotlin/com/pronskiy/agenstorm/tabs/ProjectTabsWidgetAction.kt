package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.pronskiy.agenstorm.tabs.ui.ProjectTabsPanel
import javax.swing.JComponent

/**
 * Step E1.3. The main toolbar's project slot as a strip of project tabs: one tab per open project, the frame's
 * own project highlighted, plus a "+" button. [ProjectTabsWidgetInstaller] puts this action into the
 * `main.toolbar.Project` slot while the feature is on and gives the slot back to the stock widget when it is
 * off, so this class only ever renders tabs.
 */
class ProjectTabsWidgetAction : AnAction(), CustomComponentAction, DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /** The strip handles its own clicks; the action exists to own the toolbar slot. */
    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun update(e: AnActionEvent) {
        e.presentation.putClientProperty(PROJECT_KEY, e.project)
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
        ProjectTabsPanel().also(ProjectTabActions::wire)

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        (component as? ProjectTabsPanel)?.ownerProject = presentation.getClientProperty(PROJECT_KEY)
    }

    companion object {
        /** The project of the frame this toolbar belongs to; that tab is rendered as the active one. */
        val PROJECT_KEY: Key<Project> = Key.create("agenstorm.tabs.project")
    }
}
