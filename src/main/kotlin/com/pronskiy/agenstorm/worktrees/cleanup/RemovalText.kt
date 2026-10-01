package com.pronskiy.agenstorm.worktrees.cleanup

import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.Plan
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.Risk
import com.pronskiy.agenstorm.worktrees.cleanup.WorktreeRemover.Outcome

/**
 * Steps T4.1–T4.2. What removing or archiving says: the confirmation — naming every file that would be lost and every
 * commit left on its branch — the reason it was refused, and how it ended. [name] is the worktree's folder name, as its
 * tab reads.
 */
object RemovalText {

    enum class Mode(val key: String) { REMOVE("remove"), ARCHIVE("archive") }

    private const val LISTED_FILES = 5

    fun title(mode: Mode): String = message(mode, "title")

    fun checking(mode: Mode, name: String): String = message(mode, "checking", name)

    fun progress(mode: Mode, name: String): String = message(mode, "progress", name)

    fun notClosed(mode: Mode, name: String): String = message(mode, "blocked.open", name)

    fun question(name: String, path: String, plan: Plan.Ready, mode: Mode = Mode.REMOVE): String {
        val ask = when {
            mode == Mode.ARCHIVE && plan.commit -> message(mode, "question.commit", name, path, plan.branch.orEmpty(), WorktreeRemover.archiveMessage(path))
            mode == Mode.ARCHIVE -> message(mode, "question", name, path, plan.branch.orEmpty())
            plan.branch != null -> message(mode, "question.branch", name, path, plan.branch)
            else -> message(mode, "question", name, path)
        }
        if (plan.risks.isEmpty()) return ask
        return ask + "\n\n" + message(mode, "risks") + plan.risks.joinToString("") { "\n• " + risk(it) }
    }

    fun confirmButton(plan: Plan.Ready, mode: Mode = Mode.REMOVE): String = message(mode, when {
        plan.stop -> "yes.stop"
        plan.risks.isEmpty() -> "yes"
        else -> "yes.anyway"
    })

    fun refused(name: String, plan: Plan, mode: Mode = Mode.REMOVE): String? = when (plan) {
        Plan.MainCheckout -> message(mode, "blocked.main")
        is Plan.Busy -> message(mode, "blocked.busy", name, plan.reason)
        is Plan.AgentRunning -> plan.lockReason?.let { message(mode, "blocked.agent", name, it) } ?: message(mode, "blocked.agent.plain", name)
        Plan.Unreadable -> message(mode, "blocked.unreadable", name)
        Plan.NoBranch -> AgenstormBundle.message("worktrees.archive.blocked.noBranch", name)
        is Plan.Ready -> null
    }

    fun outcome(name: String, outcome: Outcome, mode: Mode = Mode.REMOVE): String = when (outcome) {
        is Outcome.Failed -> message(mode, "failed", name, outcome.message)
        is Outcome.Removed -> when {
            mode == Mode.ARCHIVE -> message(mode, "done", name, outcome.branch.orEmpty())
            outcome.branch == null -> message(mode, "done", name)
            outcome.branchDeleted -> AgenstormBundle.message("worktrees.remove.done.branch", name, outcome.branch)
            else -> AgenstormBundle.message("worktrees.remove.done.kept", name, outcome.branch)
        }
    }

    private fun message(mode: Mode, key: String, vararg params: Any): String = AgenstormBundle.message("worktrees.${mode.key}.$key", *params)

    /** One risk as the confirmation lists it: `loses 2 uncommitted changes: …`, `keeps 1 commit that main lacks …`. */
    fun risk(risk: Risk): String = when (risk) {
        is Risk.Changes -> AgenstormBundle.message("worktrees.remove.risk.changes", risk.paths.size, files(risk.paths))
        is Risk.Unmerged -> when {
            risk.commits == null -> AgenstormBundle.message("worktrees.remove.risk.unknown")
            risk.branch == null -> AgenstormBundle.message("worktrees.remove.risk.detached", risk.commits)
            else -> AgenstormBundle.message("worktrees.remove.risk.unmerged", risk.commits, risk.base.orEmpty(), risk.branch)
        }
        is Risk.Running -> AgenstormBundle.message("worktrees.remove.risk.running", risk.programs.size, risk.programs.joinToString(", "))
        is Risk.Locked -> risk.reason?.let { AgenstormBundle.message("worktrees.remove.risk.locked", it) } ?: AgenstormBundle.message("worktrees.remove.risk.locked.plain")
    }

    /** At most five paths, then "and N more". */
    fun files(paths: List<String>): String {
        val listed = paths.take(LISTED_FILES).joinToString(", ")
        return if (paths.size <= LISTED_FILES) listed else AgenstormBundle.message("worktrees.remove.more", listed, paths.size - LISTED_FILES)
    }
}
