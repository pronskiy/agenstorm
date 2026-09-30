package com.pronskiy.agenstorm.worktrees.merge

import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.LockState

/**
 * Step T4.3, pure. Whether a worktree's branch can be merged back into its base, and the git steps that do it.
 *
 * The base is the branch the worktree was made from (`branch.<name>.agenstormBase`, a remote-tracking name turned into
 * its local branch), else the default branch, else the main checkout's; it must be checked out in some worktree — that
 * is where the result lands — and that worktree must be clean. The worktree itself may have changes: they are committed
 * first (`wip: merge back <name>`), which the dialog says. A busy project or a running agent's lock stops it, as it
 * stops removal (decisions 74, 86): a rebase would rewrite the files under them.
 *
 * [Strategy.SQUASH], the default: `git merge --squash <branch>` in the base's worktree, the result left staged for the
 * commit dialog — and Epic D's button — to write the message. [Strategy.REBASE]: `git rebase <base>` in the worktree,
 * then `git merge --ff-only <branch>` in the base's. Either way nothing is half-merged on the base: a conflict is
 * resolved in the worktree ([MergeRunner]).
 */
object MergePlan {

    enum class Strategy { SQUASH, REBASE }

    data class Facts(
        val isMain: Boolean,
        val branch: String?,
        /** What `git status` lists in the worktree; null when it could not be read. */
        val changes: List<String>?,
        val base: String?,
        /** Where [base] is checked out; null when nowhere. */
        val baseWorktree: String?,
        /** What `git status` lists there; null when it could not be read. */
        val baseChanges: List<String>?,
        /** Commits of [branch] that [base] lacks; null when git could not count them. */
        val ahead: Int?,
        val lock: LockState,
        val lockReason: String?,
        val busyReason: String?,
    )

    sealed interface Plan {
        data object MainCheckout : Plan
        data object NoBranch : Plan
        data object NoBase : Plan
        data class Busy(val reason: String) : Plan
        data class AgentRunning(val lockReason: String?) : Plan
        data object Unreadable : Plan
        data class BaseNotCheckedOut(val base: String) : Plan
        data class BaseDirty(val base: String, val worktree: String, val paths: List<String>) : Plan
        data class NothingToMerge(val base: String) : Plan

        /** [changes] uncommitted paths in the worktree, committed first when there are any. */
        data class Ready(val branch: String, val base: String, val baseWorktree: String, val commits: Int, val changes: List<String>) : Plan {
            val commitFirst: Boolean get() = changes.isNotEmpty()
        }
    }

    /** One git command, run in the worktree or in the base's worktree. */
    data class Step(val inBase: Boolean, val args: List<String>)

    fun plan(facts: Facts): Plan {
        if (facts.isMain) return Plan.MainCheckout
        val branch = facts.branch ?: return Plan.NoBranch
        facts.busyReason?.let { return Plan.Busy(it) }
        if (facts.lock == LockState.LIVE) return Plan.AgentRunning(facts.lockReason)
        val changes = facts.changes ?: return Plan.Unreadable
        val base = facts.base?.takeIf { it != branch } ?: return Plan.NoBase
        val baseWorktree = facts.baseWorktree ?: return Plan.BaseNotCheckedOut(base)
        val baseChanges = facts.baseChanges ?: return Plan.Unreadable
        if (baseChanges.isNotEmpty()) return Plan.BaseDirty(base, baseWorktree, baseChanges)
        val ahead = facts.ahead ?: return Plan.Unreadable
        if (ahead == 0 && changes.isEmpty()) return Plan.NothingToMerge(base)
        return Plan.Ready(branch, base, baseWorktree, ahead, changes)
    }

    fun steps(plan: Plan.Ready, strategy: Strategy, name: String): List<Step> = buildList {
        if (plan.commitFirst) {
            add(Step(inBase = false, listOf("add", "-A")))
            add(Step(inBase = false, listOf("commit", "--no-verify", "-q", "-m", commitMessage(name))))
        }
        when (strategy) {
            Strategy.SQUASH -> add(Step(inBase = true, listOf("merge", "--squash", plan.branch)))
            Strategy.REBASE -> {
                add(Step(inBase = false, listOf("rebase", plan.base)))
                add(Step(inBase = true, listOf("merge", "--ff-only", plan.branch)))
            }
        }
    }

    fun commitMessage(name: String): String = "wip: merge back $name"
}
