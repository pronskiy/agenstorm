package com.pronskiy.agenstorm.worktrees.cleanup

import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.Plan
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.Risk
import com.pronskiy.agenstorm.worktrees.cleanup.WorktreeRemover.Outcome

/**
 * Step T4.1. What the removal says: the confirmation — naming every file that would be lost and every commit left on
 * its branch — the reason a removal was refused, and how it ended. [name] is the worktree's folder name, as its tab
 * reads.
 */
object RemovalText {

    private const val LISTED_FILES = 5

    fun question(name: String, path: String, plan: Plan.Ready): String {
        val ask = plan.branch?.let { AgenstormBundle.message("worktrees.remove.question.branch", name, path, it) }
            ?: AgenstormBundle.message("worktrees.remove.question", name, path)
        if (plan.risks.isEmpty()) return ask
        return ask + "\n\n" + AgenstormBundle.message("worktrees.remove.risks") + plan.risks.joinToString("") { "\n• " + risk(it) }
    }

    fun confirmButton(plan: Plan.Ready): String =
        AgenstormBundle.message(if (plan.risks.isEmpty()) "worktrees.remove.yes" else "worktrees.remove.yes.anyway")

    fun refused(name: String, plan: Plan): String? = when (plan) {
        Plan.MainCheckout -> AgenstormBundle.message("worktrees.remove.blocked.main")
        is Plan.Busy -> AgenstormBundle.message("worktrees.remove.blocked.busy", name, plan.reason)
        is Plan.AgentRunning -> plan.lockReason?.let { AgenstormBundle.message("worktrees.remove.blocked.agent", name, it) }
            ?: AgenstormBundle.message("worktrees.remove.blocked.agent.plain", name)
        Plan.Unreadable -> AgenstormBundle.message("worktrees.remove.blocked.unreadable", name)
        is Plan.Ready -> null
    }

    fun outcome(name: String, outcome: Outcome): String = when (outcome) {
        is Outcome.Failed -> AgenstormBundle.message("worktrees.remove.failed", name, outcome.message)
        is Outcome.Removed -> when {
            outcome.branch == null -> AgenstormBundle.message("worktrees.remove.done", name)
            outcome.branchDeleted -> AgenstormBundle.message("worktrees.remove.done.branch", name, outcome.branch)
            else -> AgenstormBundle.message("worktrees.remove.done.kept", name, outcome.branch)
        }
    }

    private fun risk(risk: Risk): String = when (risk) {
        is Risk.Changes -> AgenstormBundle.message("worktrees.remove.risk.changes", risk.paths.size, files(risk.paths))
        is Risk.Unmerged -> when {
            risk.commits == null -> AgenstormBundle.message("worktrees.remove.risk.unknown")
            risk.branch == null -> AgenstormBundle.message("worktrees.remove.risk.detached", risk.commits)
            else -> AgenstormBundle.message("worktrees.remove.risk.unmerged", risk.commits, risk.base.orEmpty(), risk.branch)
        }
        is Risk.Locked -> risk.reason?.let { AgenstormBundle.message("worktrees.remove.risk.locked", it) } ?: AgenstormBundle.message("worktrees.remove.risk.locked.plain")
    }

    private fun files(paths: List<String>): String {
        val listed = paths.take(LISTED_FILES).joinToString(", ")
        return if (paths.size <= LISTED_FILES) listed else AgenstormBundle.message("worktrees.remove.more", listed, paths.size - LISTED_FILES)
    }
}
