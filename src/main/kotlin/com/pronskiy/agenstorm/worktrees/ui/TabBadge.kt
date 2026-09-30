package com.pronskiy.agenstorm.worktrees.ui

import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.status.WorktreeStatus

/**
 * Step T3.3, pure. What follows a tab's name — `●` uncommitted changes, `↑n` / `↓m` commits ahead of and behind what
 * it counts against, `⚙` busy (a guard's reason, for a worktree loaded in the IDE) or locked (Claude Code locks the
 * worktree its session works in) — unless the process that locked it has ended (`lockEnded`; Claude Code leaves the
 * lock behind), which the tooltip says instead — and the tooltip lines that say it in words. Nothing at all for a
 * clean, even, idle worktree.
 */
data class TabBadge(val parts: List<Part>, val tooltip: List<String>) {

    enum class Kind { DIRTY, AHEAD, BEHIND, WORKING }

    data class Part(val kind: Kind, val text: String)

    val text: String get() = parts.joinToString(" ") { it.text }

    companion object {
        val NONE = TabBadge(emptyList(), emptyList())

        fun of(worktree: Worktree, status: WorktreeStatus?, busyReason: String?, lockEnded: Boolean = false): TabBadge {
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
            if (busyReason != null || worktree.isLocked && !lockEnded) parts += Part(Kind.WORKING, "⚙")
            if (busyReason != null) tooltip += AgenstormBundle.message("worktrees.status.busy", busyReason)
            if (worktree.isLocked) {
                val reason = worktree.lockReason
                tooltip += when {
                    lockEnded -> AgenstormBundle.message("worktrees.status.locked.ended", reason.orEmpty())
                    reason != null -> AgenstormBundle.message("worktrees.status.locked", reason)
                    else -> AgenstormBundle.message("worktrees.status.locked.plain")
                }
            }
            return if (parts.isEmpty() && tooltip.isEmpty()) NONE else TabBadge(parts, tooltip)
        }
    }
}
