package com.pronskiy.agenstorm.terminal.agents

/**
 * Step X2.2. Pure: which open project a session belongs to. A session belongs to the open project whose folder is its
 * working directory or the nearest one above it — so a worktree under `.claude/worktrees/` goes to its own project when
 * that is open and to the main checkout's otherwise — and to none when no open project contains it (decision 101).
 */
object SessionGrouping {

    /** The base path among [basePaths] that [cwd] belongs to, or null. */
    fun owner(cwd: String, basePaths: Collection<String>): String? {
        val dir = normalize(cwd)
        return basePaths.filter { base -> normalize(base).let { dir == it || dir.startsWith("$it/") } }.maxByOrNull { normalize(it).length }
    }

    /**
     * [sessions] by the base path they belong to; sessions outside every open project are left out. Within a project
     * the oldest comes first, so a row keeps its place while names and statuses change.
     */
    fun group(sessions: Collection<LiveSession>, basePaths: Collection<String>): Map<String, List<LiveSession>> =
        sessions.mapNotNull { session -> owner(session.cwd, basePaths)?.let { it to session } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sortedWith(compareBy<LiveSession>({ it.startedAt ?: Long.MAX_VALUE }, { it.sessionId })) }

    private fun normalize(path: String): String = path.trimEnd('/').ifEmpty { "/" }
}
