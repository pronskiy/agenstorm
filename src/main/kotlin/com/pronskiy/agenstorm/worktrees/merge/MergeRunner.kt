package com.pronskiy.agenstorm.worktrees.merge

import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.LockState
import com.pronskiy.agenstorm.worktrees.cleanup.WorktreeRemover.GitResult
import com.pronskiy.agenstorm.worktrees.status.PorcelainStatusParser
import com.pronskiy.agenstorm.worktrees.status.StatusReader
import java.nio.file.Path

/**
 * Step T4.3. Gathers a worktree's [MergePlan.Facts] and runs [MergePlan.steps], through [git] — a git command (without
 * the `git`) run in a folder. Blocking; not for the EDT.
 *
 * When a step stops on conflicts, the base is never left half-merged. A squash that conflicts is undone in the base's
 * worktree (`git reset --merge`, which restores it — it was clean) and the base is merged into the worktree instead
 * (`git merge --no-edit <base>`), so the conflict is where the work is and the IDE's merge dialog can resolve it; merging
 * back again then goes through. A rebase that conflicts is left in progress in the worktree for the same reason.
 */
class MergeRunner(private val git: (Path, List<String>) -> GitResult) {

    sealed interface Outcome {
        data class Merged(val strategy: MergePlan.Strategy) : Outcome
        /** [files] conflict in the worktree, which holds the merge or rebase in progress. */
        data class Conflict(val files: List<String>) : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun facts(main: Worktree, worktree: Worktree, worktrees: List<Worktree>, lock: LockState, busyReason: String?): MergePlan.Facts {
        val dir = Path.of(worktree.path)
        val changes = status(dir)
        val branch = worktree.branch
        val base = branch?.let { base(Path.of(main.path), it, main.branch) }
        val baseWorktree = base?.let { name -> worktrees.firstOrNull { it.branch == name && it.path != worktree.path }?.path }
        val baseChanges = baseWorktree?.let { status(Path.of(it)) }
        val ahead = if (base != null && branch != null) {
            git(dir, listOf("rev-list", "--count", "$base..$branch")).takeIf { it.ok }?.output?.firstOrNull()?.trim()?.toIntOrNull()
        } else {
            null
        }
        return MergePlan.Facts(worktree.isMain, branch, changes, base, baseWorktree, baseChanges, ahead, lock, worktree.lockReason, busyReason)
    }

    fun run(worktree: Path, plan: MergePlan.Plan.Ready, strategy: MergePlan.Strategy, name: String): Outcome {
        val base = Path.of(plan.baseWorktree)
        for (step in MergePlan.steps(plan, strategy, name)) {
            val dir = if (step.inBase) base else worktree
            val result = git(dir, step.args)
            if (result.ok) continue
            val conflicts = conflicts(dir)
            if (conflicts.isEmpty()) return Outcome.Failed(message(result))
            if (!step.inBase) return Outcome.Conflict(conflicts)
            git(base, listOf("reset", "--merge"))
            val merged = git(worktree, listOf("merge", "--no-edit", plan.base))
            val there = conflicts(worktree)
            return if (!merged.ok && there.isNotEmpty()) Outcome.Conflict(there) else Outcome.Failed(message(result))
        }
        return Outcome.Merged(strategy)
    }

    private fun status(dir: Path): List<String>? =
        git(dir, listOf("status", "--porcelain=v2", "-z")).takeIf { it.ok }?.let { PorcelainStatusParser.changedPaths(it.output.joinToString("\n")) }

    private fun conflicts(dir: Path): List<String> =
        git(dir, listOf("diff", "--name-only", "--diff-filter=U")).takeIf { it.ok }?.output?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun message(result: GitResult): String = result.error.trim().ifEmpty { result.output.joinToString("\n").trim() }

    /** The recorded base (its local branch when a remote-tracking name was recorded), else the default branch's local one, else the main checkout's branch. */
    private fun base(main: Path, branch: String, mainBranch: String?): String? {
        val recorded = git(main, listOf("config", "--get", "branch.$branch.agenstormBase")).takeIf { it.ok }?.output?.firstOrNull()?.trim()?.ifEmpty { null }
        val default = git(main, listOf("rev-parse") + StatusReader.DEFAULT_BRANCH_ARGS).takeIf { it.ok }?.output?.firstOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() && it != "origin/HEAD" }
        return listOfNotNull(recorded, default).firstNotNullOfOrNull { local(main, it) } ?: mainBranch?.takeIf { it != branch }
    }

    private fun local(main: Path, ref: String): String? =
        listOf(ref, ref.substringAfter('/', "")).filter { it.isNotEmpty() }
            .firstOrNull { git(main, listOf("rev-parse", "--verify", "--quiet", "refs/heads/$it")).ok }
}
