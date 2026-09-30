package com.pronskiy.agenstorm.worktrees

/** Step T1.3. One tab of the worktree strip. */
data class WorktreeTab(val worktree: Worktree, val label: String, val isCurrent: Boolean) {
    val path: String get() = worktree.path
}

/**
 * Step T1.3, pure. The strip's tabs for a snapshot, seen from the project at [currentPath]: the main checkout first,
 * labelled with its branch (its folder name while detached); then the linked worktrees oldest first, each labelled
 * with its folder name. Two folders with the same name — `.worktrees/foo` and Claude Code's `.claude/worktrees/foo` —
 * get their parent added, relative to the main checkout when they sit inside it, so no two tabs read the same. Ties
 * on creation time fall back to the path, so the order never shuffles between refreshes.
 */
object WorktreeTabsModel {

    fun tabs(snapshot: WorktreeSnapshot, currentPath: String?): List<WorktreeTab> {
        val ordered = snapshot.worktrees.sortedWith(compareByDescending<Worktree> { it.isMain }.thenBy { it.createdAt }.thenBy { it.path })
        val names = ordered.associateWith { if (it.isMain) it.branch ?: folderName(it.path) else folderName(it.path) }
        val clashing = names.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val mainPath = ordered.firstOrNull { it.isMain }?.path
        return ordered.map { worktree ->
            val name = names.getValue(worktree)
            val label = if (name in clashing && !worktree.isMain) "$name · ${parentLabel(worktree.path, mainPath)}" else name
            WorktreeTab(worktree, label, isCurrent = worktree.path == currentPath)
        }
    }

    private fun folderName(path: String) = path.trimEnd('/').substringAfterLast('/')

    private fun parentLabel(path: String, mainPath: String?): String {
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        val root = mainPath?.trimEnd('/')
        return if (root != null && parent.startsWith("$root/")) parent.removePrefix("$root/") else folderName(parent)
    }
}
