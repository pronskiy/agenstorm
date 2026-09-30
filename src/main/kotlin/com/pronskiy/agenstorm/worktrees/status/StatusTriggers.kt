package com.pronskiy.agenstorm.worktrees.status

import com.pronskiy.agenstorm.worktrees.WorktreeSnapshot

/**
 * Step T3.2, pure. Which worktrees a batch of changed paths may have given a new status: a path inside a worktree
 * (the innermost one, so `.worktrees/x/…` is `x` and not the main checkout around it) is that worktree; one under
 * `<common git dir>/worktrees/<id>/` — its index, its HEAD — is the worktree with that admin dir; anything else under
 * the common git dir — refs, `packed-refs`, the main checkout's index, the config — may move every tab's ahead/behind,
 * so it is all of them. A worktree's own `.git` file changes nothing. With fewer than two worktrees there are no tabs
 * worth a status.
 */
object StatusTriggers {

    fun affected(paths: Collection<String>, snapshot: WorktreeSnapshot): Set<String> {
        val worktrees = snapshot.worktrees
        if (worktrees.size < 2) return emptySet()
        val all = worktrees.map { it.path }.toSet()
        val common = snapshot.commonDir?.trimEnd('/')
        val byAdminId = worktrees.mapNotNull { worktree -> worktree.adminId?.let { it to worktree.path } }.toMap()
        val innermostFirst = worktrees.sortedByDescending { it.path.length }
        val affected = HashSet<String>()
        for (path in paths) {
            if (common != null && (path == common || path.startsWith("$common/"))) {
                val rest = path.removePrefix(common).removePrefix("/")
                if (!rest.startsWith("worktrees/")) return all
                byAdminId[rest.removePrefix("worktrees/").substringBefore('/')]?.let(affected::add)
                continue
            }
            val worktree = innermostFirst.firstOrNull { path == it.path || path.startsWith(it.path.trimEnd('/') + "/") } ?: continue
            val inside = path.removePrefix(worktree.path.trimEnd('/'))
            if (inside == "/.git" || inside.startsWith("/.git/")) continue
            affected += worktree.path
        }
        return affected
    }
}
