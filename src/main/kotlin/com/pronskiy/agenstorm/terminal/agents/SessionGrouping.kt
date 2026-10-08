package com.pronskiy.agenstorm.terminal.agents

/**
 * Step X2.2. Pure: which open project a session belongs to. A session belongs to the open project whose folder is its
 * working directory or the nearest one above it — so a worktree under `.claude/worktrees/` goes to its own project when
 * that is open and to the main checkout's otherwise — and to none when no open project contains it (decision 101).
 */
object SessionGrouping {

    /** The base path among [basePaths] that [cwd] belongs to, or null. */
    fun owner(cwd: String, basePaths: Collection<String>): String? = owner(cwd, basePaths.associateWith { it })

    /**
     * The base path [cwd] belongs to, given [roots] from a folder to the base path it stands for — a project's own path
     * and its real one, when a symlink leads to it (`/tmp` is `/private/tmp` on macOS, and Claude Code reports the latter).
     */
    fun owner(cwd: String, roots: Map<String, String>): String? {
        val dir = normalize(cwd)
        return roots.entries.filter { (root, _) -> normalize(root).let { dir == it || dir.startsWith("$it/") } }.maxByOrNull { normalize(it.key).length }?.value
    }

    /**
     * [sessions] by the base path they belong to; sessions outside every open project are left out. Within a project
     * the oldest comes first, so a row keeps its place while names and statuses change.
     */
    fun group(sessions: Collection<LiveSession>, basePaths: Collection<String>): Map<String, List<LiveSession>> = group(sessions, basePaths.associateWith { it })

    fun group(sessions: Collection<LiveSession>, roots: Map<String, String>): Map<String, List<LiveSession>> =
        sessions.mapNotNull { session -> owner(session.cwd, roots)?.let { it to session } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sortedWith(compareBy<LiveSession>({ it.startedAt ?: Long.MAX_VALUE }, { it.sessionId })) }

    private fun normalize(path: String): String = path.trimEnd('/').ifEmpty { "/" }
}
