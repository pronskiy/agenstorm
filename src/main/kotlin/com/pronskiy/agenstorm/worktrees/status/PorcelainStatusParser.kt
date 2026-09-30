package com.pronskiy.agenstorm.worktrees.status

/**
 * Step T3.1. What a worktree's tab says about it: [dirtyCount] entries `git status` lists (a folder of untracked
 * files counts once), and [ahead] / [behind] counted against [against] — the branch's upstream, or, with none, the
 * base the worktree was made from; null (and both counts zero) when there is nothing to count against.
 */
data class WorktreeStatus(val dirtyCount: Int, val ahead: Int, val behind: Int, val against: String?) {

    companion object {
        val CLEAN = WorktreeStatus(0, 0, 0, null)
    }
}

/**
 * Step T3.1, pure. Reads `git status --porcelain=v2 --branch -z`: the `# branch.*` headers, then one NUL-terminated
 * entry per change — `1` changed, `2` renamed or copied (followed by one more field, the original path), `u`
 * unmerged, `?` untracked; `!` (ignored) is not a change. `# branch.ab` is there only while the upstream exists, so a
 * branch whose upstream is gone counts against its base like one that never had one.
 */
object PorcelainStatusParser {

    val STATUS_ARGS = listOf("--porcelain=v2", "--branch", "-z")

    /** [branch] is null on a detached HEAD; [ahead] and [behind] are null without a live upstream. */
    data class Porcelain(val branch: String?, val upstream: String?, val ahead: Int?, val behind: Int?, val dirtyCount: Int)

    fun parse(output: String): Porcelain {
        var branch: String? = null
        var upstream: String? = null
        var ahead: Int? = null
        var behind: Int? = null
        var dirty = 0
        val fields = output.split('\u0000')
        var index = 0
        while (index < fields.size) {
            val field = fields[index++].trimStart('\n', '\r')
            when {
                field.startsWith("# branch.head ") -> branch = field.removePrefix("# branch.head ").takeUnless { it == "(detached)" }
                field.startsWith("# branch.upstream ") -> upstream = field.removePrefix("# branch.upstream ")
                field.startsWith("# branch.ab ") -> field.removePrefix("# branch.ab ").split(' ').let { counts ->
                    ahead = counts.getOrNull(0)?.removePrefix("+")?.toIntOrNull()
                    behind = counts.getOrNull(1)?.removePrefix("-")?.toIntOrNull()
                }
                field.startsWith("1 ") || field.startsWith("u ") || field.startsWith("? ") -> dirty++
                field.startsWith("2 ") -> {
                    dirty++
                    index++
                }
            }
        }
        return Porcelain(branch, upstream, ahead.takeIf { behind != null }, behind.takeIf { ahead != null }, dirty)
    }

    /** What to count against when [porcelain] has no live upstream: the recorded base, else the default branch; never the branch itself. */
    fun baseFor(porcelain: Porcelain, recordedBase: String?, defaultBranch: String?): String? {
        val branch = porcelain.branch ?: return null
        if (porcelain.ahead != null) return null
        return (recordedBase ?: defaultBranch)?.takeIf { it != branch && it.isNotBlank() }
    }

    /** The arguments of `git rev-list` that count the commits of `<base>...HEAD` on each side. */
    fun countArgs(base: String): List<String> = listOf("--left-right", "--count", "$base...HEAD")

    /** `git rev-list --left-right --count <base>...HEAD` prints `<behind>\t<ahead>`: the left side is the base's own commits. */
    fun parseCounts(output: String): Pair<Int, Int>? {
        val parts = output.trim().split('\t', ' ').filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val behind = parts[0].toIntOrNull() ?: return null
        val ahead = parts[1].toIntOrNull() ?: return null
        return ahead to behind
    }

    /** [counts] is [parseCounts]'s answer for [base] (null when there was no base or git failed). */
    fun status(porcelain: Porcelain, base: String?, counts: Pair<Int, Int>?): WorktreeStatus = when {
        porcelain.ahead != null && porcelain.behind != null -> WorktreeStatus(porcelain.dirtyCount, porcelain.ahead, porcelain.behind, porcelain.upstream)
        base != null && counts != null -> WorktreeStatus(porcelain.dirtyCount, counts.first, counts.second, base)
        else -> WorktreeStatus(porcelain.dirtyCount, 0, 0, null)
    }
}
