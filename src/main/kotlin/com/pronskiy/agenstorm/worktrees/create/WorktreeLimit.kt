package com.pronskiy.agenstorm.worktrees.create

/**
 * Step T5.2 (decision 94). A cap on a repository's worktrees, Air's idea: off by default, 15 when on. Every linked
 * worktree counts, whoever made it — an agent's `claude -w` one takes a slot like "+"'s; the main checkout does not.
 */
object WorktreeLimit {

    const val DEFAULT: Int = 15
    val RANGE: IntRange = 1..100

    /** Whether "+" must refuse: the limit is on and [linked] worktrees already fill it. */
    fun reached(linked: Int, enabled: Boolean, limit: Int): Boolean = enabled && linked >= limit
}
