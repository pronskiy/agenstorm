package com.pronskiy.agenstorm.terminal.agents

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.pronskiy.agenstorm.terminal.tmux.BackgroundTerminals
import com.pronskiy.agenstorm.terminal.tmux.TmuxTabs

/** Step X2.6. What a click on a row does (decision 103). */
sealed interface ClickPlan {
    /** Bring up [tab] in its project's window. */
    data class Focus(val tab: ProjectTab) : ClickPlan

    /** Bring the Epic U background terminal [tmuxSession] back as a tab of the project at [basePath], then show it. */
    data class Background(val tmuxSession: String, val basePath: String) : ClickPlan

    /** Runs outside the IDE: nothing to show. */
    data object Nothing : ClickPlan

    /** Step X3.3. A past session: a new Terminal tab of the project at [basePath], in [cwd], running `claude --resume`. */
    data class Resume(val basePath: String, val cwd: String, val sessionId: String, val title: String) : ClickPlan

    companion object {
        /** Pure. */
        fun of(row: SessionRow, basePath: String): ClickPlan = when (val place = row.place) {
            is SessionPlace.InTab -> Focus(place.tab)
            is SessionPlace.Background -> Background(place.tmuxSession, basePath)
            SessionPlace.Elsewhere -> Nothing
        }

        /** Pure. */
        fun of(past: PastSession, basePath: String): ClickPlan = Resume(basePath, past.cwd, past.sessionId, past.title)
    }
}

/** Step X2.6. Carries a [ClickPlan] out, EDT. */
object AgentSessionOpener {

    fun open(plan: ClickPlan) {
        when (plan) {
            is ClickPlan.Focus -> {
                val project = plan.tab.project
                if (project.isDisposed) return
                ProjectUtil.focusProjectWindow(project, true)
                plan.tab.tab.content.manager?.setSelectedContent(plan.tab.tab.content)
                TmuxTabs.showToolWindow(project, focus = true)
            }
            is ClickPlan.Background -> {
                val project = projectAt(plan.basePath) ?: return
                val session = BackgroundTerminals.getInstance().sessions.value.firstOrNull { it.name == plan.tmuxSession } ?: return
                ProjectUtil.focusProjectWindow(project, true)
                BackgroundTerminals.getInstance().open(project, session)
            }
            ClickPlan.Nothing -> Unit
            is ClickPlan.Resume -> {
                val project = projectAt(plan.basePath) ?: return
                ProjectUtil.focusProjectWindow(project, true)
                AgentResume.start(project, plan.cwd, plan.sessionId, plan.title)
            }
        }
    }

    private fun projectAt(basePath: String): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed && it.basePath == basePath }
}
