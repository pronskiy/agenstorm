package com.pronskiy.agenstorm.worktrees.ui

/**
 * Step T1.5, pure. Which tabs of the worktree strip fit in [available] pixels. Every tab has its natural width and
 * [gap] after it; the "+" takes [fixed] and is always there; when anything is left out, the "»" takes [more]. Tabs keep
 * their order, and the current tab is never the one left out: if it would not fit, it takes the place of the last
 * tabs that did. With no room at all the current tab is still shown and the layout clips it.
 */
object StripLayout {

    data class Plan(val visible: List<Int>, val overflow: List<Int>)

    fun plan(widths: List<Int>, current: Int, available: Int, gap: Int, fixed: Int, more: Int): Plan {
        val all = widths.indices.toList()
        if (widths.sumOf { it + gap } + fixed <= available) return Plan(all, emptyList())

        var budget = available - fixed - more
        val visible = mutableListOf<Int>()
        for (index in all) {
            val width = widths[index] + gap
            if (width > budget) break
            visible += index
            budget -= width
        }
        if (current in widths.indices && current !in visible) {
            val needed = widths[current] + gap
            while (visible.isNotEmpty() && budget < needed) budget += widths[visible.removeAt(visible.lastIndex)] + gap
            visible += current
        }
        visible.sort()
        return Plan(visible, all - visible.toSet())
    }
}
