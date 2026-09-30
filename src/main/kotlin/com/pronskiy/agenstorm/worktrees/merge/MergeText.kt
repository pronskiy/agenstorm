package com.pronskiy.agenstorm.worktrees.merge

import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalText
import com.pronskiy.agenstorm.worktrees.merge.MergePlan.Plan
import com.pronskiy.agenstorm.worktrees.merge.MergeRunner.Outcome

/** Step T4.3. What merging back says when it is refused and how it ended. [name] is the worktree's folder name. */
object MergeText {

    fun refused(name: String, plan: Plan): String? = when (plan) {
        Plan.MainCheckout -> AgenstormBundle.message("worktrees.merge.blocked.main")
        Plan.NoBranch -> AgenstormBundle.message("worktrees.merge.blocked.noBranch", name)
        Plan.NoBase -> AgenstormBundle.message("worktrees.merge.blocked.noBase", name)
        is Plan.Busy -> AgenstormBundle.message("worktrees.merge.blocked.busy", name, plan.reason)
        is Plan.AgentRunning -> plan.lockReason?.let { AgenstormBundle.message("worktrees.merge.blocked.agent", name, it) }
            ?: AgenstormBundle.message("worktrees.merge.blocked.agent.plain", name)
        Plan.Unreadable -> AgenstormBundle.message("worktrees.merge.blocked.unreadable", name)
        is Plan.BaseNotCheckedOut -> AgenstormBundle.message("worktrees.merge.blocked.notCheckedOut", name, plan.base)
        is Plan.BaseDirty -> AgenstormBundle.message("worktrees.merge.blocked.baseDirty", name, plan.base, plan.worktree, RemovalText.files(plan.paths))
        is Plan.NothingToMerge -> AgenstormBundle.message("worktrees.merge.blocked.nothing", name, plan.base)
        is Plan.Ready -> null
    }

    fun outcome(name: String, plan: Plan.Ready, baseWorktree: String, outcome: Outcome): String = when (outcome) {
        is Outcome.Merged -> when (outcome.strategy) {
            MergePlan.Strategy.SQUASH -> AgenstormBundle.message("worktrees.merge.done.squash", name, plan.base, baseWorktree)
            MergePlan.Strategy.REBASE -> AgenstormBundle.message("worktrees.merge.done.rebase", name, plan.base)
        }
        is Outcome.Conflict -> AgenstormBundle.message("worktrees.merge.conflict", name, plan.base, RemovalText.files(outcome.files))
        is Outcome.Failed -> AgenstormBundle.message("worktrees.merge.failed", name, plan.base, outcome.message)
    }
}
