package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

/**
 * Step E2.3. Keyboard counterparts of the strip: next / previous project tab (cyclic, in tab order) and close the
 * current one. Registered in `plugin.xml` without default shortcuts, so they never collide with a keymap; the
 * README suggests some.
 */
abstract class ProjectTabNavigationAction(private val step: Int) : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && ProjectTabsModel.getInstance().tabs().size > 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = cyclicNeighbour(ProjectTabsModel.getInstance().tabs(), project, step) ?: return
        ProjectTabActions.switchTo(target, from = project)
    }

    companion object {
        /** The tab [step] positions away from [current], wrapping around; null when there is nowhere to go. */
        fun cyclicNeighbour(tabs: List<Project>, current: Project?, step: Int): Project? {
            if (tabs.size < 2) return null
            val index = tabs.indexOf(current)
            if (index < 0) return tabs.first()
            return tabs[((index + step) % tabs.size + tabs.size) % tabs.size]
        }
    }
}

class NextProjectTabAction : ProjectTabNavigationAction(+1)

class PrevProjectTabAction : ProjectTabNavigationAction(-1)

/** Closes the frame's project the way the tab's × does: switching to a neighbour first when there is one. */
class CloseProjectTabAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ProjectTabActions.close(project, owner = project, tabs = ProjectTabsModel.getInstance().tabs())
    }
}
