package com.pronskiy.agenstorm.worktrees.cleanup

import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.status.PorcelainStatusParser
import com.pronskiy.agenstorm.worktrees.status.StatusReader
import java.nio.file.Path

/**
 * Step T4.1. Gathers a worktree's [RemovalPlan.Facts] and carries out a [RemovalPlan.Plan.Ready], through [git] — a
 * git command (without the `git`) run in a folder. Blocking; not for the EDT.
 *
 * The base the commits are counted against is the one T2.1 recorded (`branch.<name>.agenstormBase`), else the main
 * checkout's branch, else the default branch — the branch the work would be merged into. On a detached HEAD there is
 * no branch to keep the commits, so what counts is the commits on no branch and no remote. Removal runs in the main
 * checkout: the lock is lifted if the plan says so (and put back if the removal then fails), `git worktree remove`
 * (`--force` only when the plan forces it), then `git branch -d`, which keeps a branch that is not merged. Archiving
 * (T4.2) first commits everything `git status` lists to the worktree's branch, hooks skipped — a failed commit stops it
 * before anything is removed — and leaves the branch alone.
 */
class WorktreeRemover(private val git: (Path, List<String>) -> GitResult) {

    data class GitResult(val ok: Boolean, val output: List<String>, val error: String)

    sealed interface Outcome {
        /** [branchDeleted] false: [branch] is kept, not merged — or there was none. */
        data class Removed(val branch: String?, val branchDeleted: Boolean) : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun facts(main: Worktree, worktree: Worktree, lock: RemovalPlan.LockState, busyReason: String?): RemovalPlan.Facts {
        val dir = Path.of(worktree.path)
        val status = git(dir, listOf("status", "--porcelain=v2", "-z"))
        val changes = if (status.ok) PorcelainStatusParser.changedPaths(status.output.joinToString("\n")) else null
        val branch = worktree.branch
        val base = if (branch == null) null else base(Path.of(main.path), branch, main.branch)
        val count = when {
            branch == null -> listOf("rev-list", "--count", "HEAD", "--not", "--branches", "--remotes")
            base != null -> listOf("rev-list", "--count", "$base..HEAD")
            else -> null
        }
        val unmerged = count?.let { args -> git(dir, args).takeIf { it.ok }?.output?.firstOrNull()?.trim()?.toIntOrNull() }
        return RemovalPlan.Facts(worktree.isMain, branch, changes, unmerged, base, lock, worktree.lockReason, busyReason)
    }

    fun remove(main: Path, worktree: Worktree, plan: RemovalPlan.Plan.Ready): Outcome {
        val path = worktree.path
        if (plan.commit) {
            val dir = Path.of(path)
            val added = git(dir, listOf("add", "-A"))
            val committed = if (added.ok) git(dir, listOf("commit", "--no-verify", "-q", "-m", archiveMessage(path))) else added
            if (!committed.ok) return Outcome.Failed(committed.error.trim().ifEmpty { committed.output.joinToString("\n").trim() })
        }
        val unlocked = plan.unlock && git(main, listOf("worktree", "unlock", path)).ok
        val removed = git(main, listOfNotNull("worktree", "remove", "--force".takeIf { plan.force }, path))
        if (!removed.ok) {
            if (unlocked) git(main, listOf("worktree", "lock") + (worktree.lockReason?.let { listOf("--reason", it) } ?: emptyList()) + path)
            return Outcome.Failed(removed.error.trim().ifEmpty { removed.output.joinToString("\n").trim() })
        }
        val branch = plan.branch ?: return Outcome.Removed(null, branchDeleted = false)
        if (!plan.deleteBranch) return Outcome.Removed(branch, branchDeleted = false)
        return Outcome.Removed(branch, branchDeleted = git(main, listOf("branch", "-d", branch)).ok)
    }

    private fun base(main: Path, branch: String, mainBranch: String?): String? {
        val recorded = git(main, listOf("config", "--get", "branch.$branch.agenstormBase")).takeIf { it.ok }?.output?.firstOrNull()?.trim()?.ifEmpty { null }
        if (recorded != null) return recorded
        if (mainBranch != null && mainBranch != branch) return mainBranch
        return git(main, listOf("rev-parse") + StatusReader.DEFAULT_BRANCH_ARGS).takeIf { it.ok }?.output?.firstOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() && it != "origin/HEAD" }
    }

    companion object {
        /** The archive commit's message: `wip: archive <folder name>`. */
        fun archiveMessage(path: String): String = "wip: archive ${path.trimEnd('/').substringAfterLast('/')}"
    }
}
