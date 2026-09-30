package com.pronskiy.agenstorm.worktrees.status

import java.nio.file.Path

/**
 * Step T3.2. Reads one worktree's [WorktreeStatus] through [git] — the command, run in the given folder, returning its
 * output lines or null when git failed. One `git status` per read, plus one `git rev-list` when the branch has no live
 * upstream. The bases to count against are read once and kept until [forgetBases]: every `branch.<name>.agenstormBase`
 * T2.1 recorded (`git config --get-regexp`, which exits 1 when there is none) and the default branch (`origin/HEAD`).
 * Blocking; not for the EDT.
 */
class StatusReader(private val git: (Path, Command, List<String>) -> List<String>?) {

    enum class Command { STATUS, REV_LIST, CONFIG, REV_PARSE }

    data class Bases(val recorded: Map<String, String>, val defaultBranch: String?)

    @Volatile
    private var bases: Bases? = null

    fun forgetBases() {
        bases = null
    }

    fun read(worktree: Path): WorktreeStatus? {
        val output = git(worktree, Command.STATUS, PorcelainStatusParser.STATUS_ARGS) ?: return null
        val porcelain = PorcelainStatusParser.parse(output.joinToString("\n"))
        val branch = porcelain.branch
        val base = if (branch != null && porcelain.ahead == null) {
            val known = bases ?: loadBases(worktree).also { bases = it }
            PorcelainStatusParser.baseFor(porcelain, known.recorded[branch], known.defaultBranch)
        } else {
            null
        }
        val counts = base?.let { git(worktree, Command.REV_LIST, PorcelainStatusParser.countArgs(it))?.firstOrNull()?.let(PorcelainStatusParser::parseCounts) }
        return PorcelainStatusParser.status(porcelain, base, counts)
    }

    private fun loadBases(dir: Path): Bases {
        val recorded = git(dir, Command.CONFIG, RECORDED_BASES_ARGS).orEmpty()
        val default = git(dir, Command.REV_PARSE, DEFAULT_BRANCH_ARGS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "origin/HEAD" }
        return Bases(parseRecordedBases(recorded), default)
    }

    companion object {
        private const val SUFFIX = ".agenstormbase"
        val RECORDED_BASES_ARGS = listOf("--get-regexp", "^branch\\..*\\.agenstormbase$")
        val DEFAULT_BRANCH_ARGS = listOf("--abbrev-ref", "origin/HEAD")

        /** `branch.<name>.agenstormbase <base>` lines — git lowercases the key but keeps the branch name's case — keyed by branch. */
        fun parseRecordedBases(lines: List<String>): Map<String, String> = lines.mapNotNull { line ->
            val key = line.substringBefore(' ')
            val value = line.substringAfter(' ', "").trim()
            if (!key.startsWith("branch.") || !key.endsWith(SUFFIX) || value.isEmpty()) return@mapNotNull null
            key.removePrefix("branch.").removeSuffix(SUFFIX).takeIf { it.isNotEmpty() }?.let { it to value }
        }.toMap()
    }
}
