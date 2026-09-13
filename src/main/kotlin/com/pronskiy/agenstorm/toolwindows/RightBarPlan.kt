package com.pronskiy.agenstorm.toolwindows

import com.intellij.openapi.wm.ToolWindowAnchor

/** One tool window as far as Epic N is concerned: which side of the window it is anchored to. */
data class ToolWindowSide(val id: String, val anchor: ToolWindowAnchor)

/**
 * Epic N. What to move off the right side, what to put back, and what is still owed — the only logic in the feature,
 * kept clear of the platform so the awkward cases can be tested directly.
 *
 * The debt matters because an anchor is persisted in the project's workspace layout, not held in memory: after a
 * restart a window Agenstorm moved looks exactly like one the user moved, and without a record, turning the feature
 * off would shove windows onto the right that were never there.
 */
object RightBarPlan {

    class Plan(
        /** Windows to move off the right side now. */
        val moveLeft: List<String>,
        /** Windows to put back on the right now — only ones Agenstorm moved itself. */
        val moveBack: List<String>,
        /** What is still owed after this pass; replaces the stored record. */
        val remember: Set<String>,
    )

    fun plan(windows: List<ToolWindowSide>, remembered: Set<String>, clearRight: Boolean): Plan {
        val byId = windows.associateBy { it.id }
        // A remembered window that is gone is nobody's debt, and one the user has since moved somewhere of their own
        // choosing is theirs now — only the ones still sitting where Agenstorm parked them are still owed.
        val stillOwed = remembered.filter { byId[it]?.anchor == ToolWindowAnchor.LEFT }

        if (!clearRight) return Plan(moveLeft = emptyList(), moveBack = stillOwed, remember = emptySet())

        val moveLeft = windows.filter { it.anchor == ToolWindowAnchor.RIGHT }.map { it.id }
        return Plan(moveLeft = moveLeft, moveBack = emptyList(), remember = (stillOwed + moveLeft).toSet())
    }
}
