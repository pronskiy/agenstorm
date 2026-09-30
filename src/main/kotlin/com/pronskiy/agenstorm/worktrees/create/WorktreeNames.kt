package com.pronskiy.agenstorm.worktrees.create

/**
 * Step T2.1, pure. The name typed into "New Worktree" becomes one slug that is both the folder under `.worktrees/`
 * and the new branch: lower case, anything but letters, digits, `.`, `_` and `-` turned into `-`, runs of `-`
 * collapsed, no leading or trailing `-` or `.`, at most [MAX] characters. Null when nothing usable is left.
 */
object WorktreeNames {

    const val MAX = 60

    fun slug(input: String): String? {
        val slug = input.trim().lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-', '.')
            .take(MAX)
            .trimEnd('-', '.')
            .removeSuffix(".lock")
        return slug.ifEmpty { null }
    }

    /** Step T4.2: the name a worktree for an existing [branch] gets unless one is typed — its last segment, as a slug. */
    fun fromBranch(branch: String): String? = slug(branch.substringAfterLast('/')) ?: slug(branch)
}

/** Step T2.1, pure. `info/exclude` with [entry] added once; null when it is there already, so the file is left alone. */
object ExcludeFile {

    fun withEntry(content: String?, entry: String): String? {
        val lines = content.orEmpty().lines()
        if (lines.any { it.trim() == entry }) return null
        val text = content.orEmpty()
        val separator = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
        return "$text$separator$entry\n"
    }
}
