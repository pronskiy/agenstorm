package com.pronskiy.agenstorm.worktrees.cleanup

/**
 * Step T4.1, pure. Whether a worktree may be removed, what removing it would lose or override, and how git is told.
 *
 * Some things stop it outright, whatever the user says: the main checkout; a worktree whose project is loaded here and
 * busy (a guard's reason — the same rule as the switch, decision 74); and one locked by a process that still runs
 * (decision 86 — an agent at work). A worktree git cannot read the status of is not removed either. Everything else is
 * [Plan.Ready], with the [Risk]s the user confirms before anything happens: uncommitted changes (lost — `--force`),
 * commits the base lacks (kept on the branch, which `git branch -d` then refuses to delete; on a detached HEAD, commits
 * on no branch at all, which are lost), and a lock that names no process (overridden). A lock whose process has ended
 * is no risk — Claude Code leaves one behind after every session — but it is lifted first, since `git worktree remove`
 * refuses a locked worktree.
 *
 * Step T4.2: [archive] keeps the work instead — whatever `git status` lists is committed to the worktree's branch, the
 * worktree is removed without `--force` and the branch stays, so *New Worktree* can bring it back; commits the base
 * lacks are no risk then. A detached HEAD has no branch to keep the work on, so it is not archived.
 *
 * Step U4.1: a program running in the worktree's terminals ([Facts.running] — a tmux session outlives its window, so the
 * project's busy guards do not see it) is a risk to confirm, first in the list, and the confirmation reads *Stop and
 * Remove*: [Plan.Ready.stop] ends it before the window closes, so a switch away from the worktree does not hand it on.
 */
object RemovalPlan {

    enum class LockState { NONE, LIVE, ENDED, UNKNOWN }

    data class Facts(
        val isMain: Boolean,
        val branch: String?,
        /** What `git status` lists; null when it could not be read. */
        val changes: List<String>?,
        /** Commits of HEAD that [base] lacks — or, on a detached HEAD, that are on no branch; null when git could not count them. */
        val unmerged: Int?,
        val base: String?,
        val lock: LockState,
        val lockReason: String?,
        /** A guard's reason, for a worktree whose project is loaded in the IDE. */
        val busyReason: String?,
        /** What runs in the worktree's terminals, whether its window is open or not (U4.1). */
        val running: List<String> = emptyList(),
    )

    sealed interface Risk {
        data class Changes(val paths: List<String>) : Risk

        /** [commits] null: git could not tell. [base] null: counted against every branch (a detached HEAD). */
        data class Unmerged(val commits: Int?, val base: String?, val branch: String?) : Risk

        data class Locked(val reason: String?) : Risk

        data class Running(val programs: List<String>) : Risk
    }

    sealed interface Plan {
        data object MainCheckout : Plan
        data class Busy(val reason: String) : Plan
        data class AgentRunning(val lockReason: String?) : Plan
        data object Unreadable : Plan
        data object NoBranch : Plan

        /**
         * [risks] empty: nothing is lost. [force] removes a worktree with changes; [unlock] lifts its lock first; [commit]
         * commits the changes to [branch] before (archiving); [deleteBranch] asks `git branch -d` after.
         */
        data class Ready(
            val risks: List<Risk>,
            val unlock: Boolean,
            val force: Boolean,
            val branch: String?,
            val commit: Boolean = false,
            val deleteBranch: Boolean = true,
        ) : Plan {
            /** Something runs in the worktree's terminals: confirming stops it. */
            val stop: Boolean get() = risks.any { it is Risk.Running }
        }
    }

    fun plan(facts: Facts): Plan {
        stopped(facts)?.let { return it }
        val changes = facts.changes ?: return Plan.Unreadable
        val unmerged = facts.unmerged
        val risks = listOfNotNull(
            running(facts),
            Risk.Changes(changes).takeIf { changes.isNotEmpty() },
            Risk.Unmerged(unmerged, facts.base, facts.branch).takeIf { unmerged == null || unmerged > 0 },
            Risk.Locked(facts.lockReason).takeIf { facts.lock == LockState.UNKNOWN },
        )
        return Plan.Ready(risks, unlock = facts.lock != LockState.NONE, force = changes.isNotEmpty(), branch = facts.branch)
    }

    fun archive(facts: Facts): Plan {
        stopped(facts)?.let { return it }
        val changes = facts.changes ?: return Plan.Unreadable
        val branch = facts.branch ?: return Plan.NoBranch
        val risks = listOfNotNull(running(facts), Risk.Locked(facts.lockReason).takeIf { facts.lock == LockState.UNKNOWN })
        return Plan.Ready(risks, unlock = facts.lock != LockState.NONE, force = false, branch = branch, commit = changes.isNotEmpty(), deleteBranch = false)
    }

    private fun running(facts: Facts): Risk? = Risk.Running(facts.running).takeIf { facts.running.isNotEmpty() }

    private fun stopped(facts: Facts): Plan? = when {
        facts.isMain -> Plan.MainCheckout
        facts.busyReason != null -> Plan.Busy(facts.busyReason)
        facts.lock == LockState.LIVE -> Plan.AgentRunning(facts.lockReason)
        else -> null
    }
}
