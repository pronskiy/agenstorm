package com.pronskiy.agenstorm.worktrees.cleanup

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.text.DateFormatUtil
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.Plan
import com.pronskiy.agenstorm.worktrees.cleanup.StaleWorktrees.Candidate
import javax.swing.JComponent

/** Step T5.3: the worktrees a cleanup may take, ticked, with when each was last used; below, the rest and what keeps each. */
class CleanUpWorktreesDialog(project: Project, private val split: StaleWorktrees.Split) : DialogWrapper(project) {

    private val ticked = split.removable.associate { it.name to true }.toMutableMap()

    val chosen: Set<String> get() = ticked.filterValues { it }.keys

    init {
        title = AgenstormBundle.message("worktrees.cleanup.title")
        setOKButtonText(AgenstormBundle.message("worktrees.cleanup.remove"))
        init()
        isOKActionEnabled = split.removable.isNotEmpty()
    }

    override fun createCenterPanel(): JComponent = panel {
        if (split.removable.isEmpty()) row { label(AgenstormBundle.message("worktrees.cleanup.none")) }
        else {
            row { label(AgenstormBundle.message("worktrees.cleanup.intro")) }
            for (candidate in split.removable) row {
                checkBox(AgenstormBundle.message("worktrees.cleanup.row", candidate.name, DateFormatUtil.formatPrettyDateTime(candidate.lastActive)))
                    .bindSelected({ ticked[candidate.name] == true }, { ticked[candidate.name] = it })
            }
        }
        if (split.kept.isNotEmpty()) group(AgenstormBundle.message("worktrees.cleanup.kept")) {
            for (candidate in split.kept) row { label(AgenstormBundle.message("worktrees.cleanup.kept.row", candidate.name, reason(candidate))).enabled(false) }
        }
    }

    companion object {
        /** What keeps [candidate]: `open in a window`, `removing it loses 2 uncommitted changes: …`, a guard's reason. */
        fun reason(candidate: Candidate): String = when {
            candidate.open -> AgenstormBundle.message("worktrees.cleanup.reason.open")
            else -> when (val plan = candidate.plan) {
                is Plan.Ready -> plan.risks.firstOrNull()?.let { AgenstormBundle.message("worktrees.cleanup.reason.risk", RemovalText.risk(it)) }.orEmpty()
                is Plan.Busy -> plan.reason
                is Plan.AgentRunning -> plan.lockReason?.let { AgenstormBundle.message("worktrees.cleanup.reason.agent", it) } ?: AgenstormBundle.message("worktrees.cleanup.reason.agent.plain")
                Plan.Unreadable -> AgenstormBundle.message("worktrees.cleanup.reason.unreadable")
                Plan.MainCheckout, Plan.NoBranch -> ""
            }
        }
    }
}
