package com.pronskiy.agenstorm.worktrees.ui

import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.status.WorktreeStatus

/**
 * Step T3.3, pure. What follows a tab's name — `●` uncommitted changes, `↑n` / `↓m` commits ahead of and behind what
 * it counts against, `⚙` busy (a guard's reason, for a worktree loaded in the IDE) or locked (Claude Code locks the
 * worktree its agent works in) — and the tooltip lines that say it in words. Nothing at all for a clean, even, idle
 * worktree.
 */
data class TabBadge(val parts: List<Part>, val tooltip: List<String>) {

    enum class Kind { DIRTY, AHEAD, BEHIND, WORKING }

    data class Part(val kind: Kind, val text: String)

    val text: String get() = parts.joinToString(" ") { it.text }

    companion object {
        val NONE = TabBadge(emptyList(), emptyList())

        fun of(worktree: Worktree, status: WorktreeStatus?, busyReason: String?): TabBadge {
            val parts = mutableListOf<Part>()
            val tooltip = mutableListOf<String>()
            if (status != null && status.dirtyCount > 0) {
                parts += Part(Kind.DIRTY, "●")
                tooltip += AgenstormBundle.message("worktrees.status.dirty", status.dirtyCount)
            }
            if (status?.against != null && status.ahead > 0) {
                parts += Part(Kind.AHEAD, "↑${status.ahead}")
                tooltip += AgenstormBundle.message("worktrees.status.ahead", status.ahead, status.against)
            }
            if (status?.against != null && status.behind > 0) {
                parts += Part(Kind.BEHIND, "↓${status.behind}")
                tooltip += AgenstormBundle.message("worktrees.status.behind", status.behind, status.against)
            }
            if (busyReason != null || worktree.isLocked) {
                parts += Part(Kind.WORKING, "⚙")
                if (busyReason != null) tooltip += AgenstormBundle.message("worktrees.status.busy", busyReason)
                if (worktree.isLocked) tooltip += worktree.lockReason?.let { AgenstormBundle.message("worktrees.status.locked", it) } ?: AgenstormBundle.message("worktrees.status.locked.plain")
            }
            return if (parts.isEmpty()) NONE else TabBadge(parts, tooltip)
        }
    }
}
