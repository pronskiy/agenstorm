package com.pronskiy.agenstorm.worktrees.carry

import com.pronskiy.agenstorm.worktrees.cleanup.WorktreeRemover.GitResult
import com.pronskiy.agenstorm.worktrees.status.PorcelainStatusParser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Step T5.1 (decision 94). Copies the uncommitted work of a checkout into a new worktree made from the same HEAD, the
 * source left exactly as it was — what Air does for a temporary worktree. Tracked changes, staged and unstaged, travel
 * as `git stash create`: a stash commit made without touching the working tree or the stash list, which the new worktree
 * can apply because worktrees share one object store. `apply --index` keeps what was staged staged; where the index
 * cannot be restored the changes arrive unstaged. Untracked files git does not ignore are copied as they are (the
 * ignored ones are T2.2's business). [git] runs a git command in a folder. Blocking: call it off the EDT.
 */
class ChangeCarry(private val git: (Path, List<String>) -> GitResult) {

    sealed interface Outcome {
        /** [unstaged]: the index could not be restored, so what was staged arrived unstaged. */
        data class Carried(val tracked: Boolean, val untracked: Int, val unstaged: Boolean) : Outcome

        data class Failed(val message: String) : Outcome
    }

    /** What would come along — the paths `git status` lists, each untracked file on its own; none when git cannot tell. */
    fun changes(source: Path): List<String> {
        val status = git(source, listOf("status", "--porcelain=v2", "-z", "--untracked-files=all"))
        return if (status.ok) PorcelainStatusParser.changedPaths(status.output.joinToString("\n")) else emptyList()
    }

    fun carry(source: Path, target: Path): Outcome {
        val created = git(source, listOf("stash", "create"))
        if (!created.ok) return Outcome.Failed(created.error.trim())
        val stash = created.output.firstOrNull()?.trim().orEmpty()
        var unstaged = false
        if (stash.isNotEmpty() && !git(target, listOf("stash", "apply", "--index", stash)).ok) {
            val plain = git(target, listOf("stash", "apply", stash))
            if (!plain.ok) return Outcome.Failed(plain.error.trim())
            unstaged = true
        }
        val listed = git(source, listOf("ls-files", "--others", "--exclude-standard", "-z"))
        if (!listed.ok) return Outcome.Failed(listed.error.trim())
        val untracked = untrackedPaths(listed.output.joinToString("\n")).count { copy(source.resolve(it), target.resolve(it)) }
        return Outcome.Carried(tracked = stash.isNotEmpty(), untracked = untracked, unstaged = unstaged)
    }

    private fun copy(from: Path, to: Path): Boolean = try {
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) false
        else {
            to.parent?.let(Files::createDirectories)
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
            true
        }
    } catch (_: IOException) {
        false
    }

    companion object {
        /** `git ls-files --others -z`: one path per NUL; a nested repository shows as its folder (`lib/`) and is not copied. */
        fun untrackedPaths(output: String): List<String> =
            output.split('\u0000').map { it.trim('\n', '\r') }.filter { it.isNotEmpty() && !it.endsWith('/') }
    }
}
