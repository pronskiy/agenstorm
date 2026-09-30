package com.pronskiy.agenstorm.worktrees

/**
 * Step T1.2. One working tree of the project's repository, as the worktree strip shows it. [path] is
 * system-independent; [branch] is the short name, null while the worktree is on a detached HEAD; [lockReason] is
 * what `git worktree lock --reason` recorded (Claude Code locks a worktree while its agent runs); [createdAt] is
 * when git made the worktree's admin dir, 0 for the main checkout, which has none.
 */
data class Worktree(
    val path: String,
    val branch: String?,
    val isMain: Boolean,
    val isLocked: Boolean,
    val lockReason: String?,
    val createdAt: Long,
)

/** The worktrees of one repository. [commonDir] is the shared git dir, which is how two projects tell they belong to the same repository. */
data class WorktreeSnapshot(val commonDir: String?, val worktrees: List<Worktree>) {

    companion object {
        val EMPTY = WorktreeSnapshot(null, emptyList())
    }
}
