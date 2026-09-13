package com.pronskiy.agenstorm.toolwindows

import com.intellij.openapi.wm.ToolWindowAnchor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Step N1.1. The whole decision of Epic N: given where the tool windows sit right now and which ones Agenstorm
 * remembers moving off the right, what does it move, what does it put back, and what does it still owe. No live
 * `ToolWindowManager`, so the awkward cases — a window the user moved somewhere else, one that is gone, one dragged
 * back to the right — are exercised here rather than by eye.
 */
class RightBarPlanTest {

    private fun at(id: String, anchor: ToolWindowAnchor) = ToolWindowSide(id, anchor)
    private fun right(id: String) = at(id, ToolWindowAnchor.RIGHT)
    private fun left(id: String) = at(id, ToolWindowAnchor.LEFT)

    @Test
    fun `moves every right-anchored window off and remembers it`() {
        val plan = RightBarPlan.plan(listOf(right("Database"), right("Notifications"), left("Project")), remembered = emptySet(), clearRight = true)

        assertEquals(listOf("Database", "Notifications"), plan.moveLeft)
        assertEquals(emptyList<String>(), plan.moveBack)
        assertEquals(setOf("Database", "Notifications"), plan.remember)
    }

    @Test
    fun `leaves the other sides alone`() {
        val windows = listOf(left("Project"), at("Terminal", ToolWindowAnchor.BOTTOM), at("Run", ToolWindowAnchor.TOP))

        val plan = RightBarPlan.plan(windows, remembered = emptySet(), clearRight = true)

        assertEquals(emptyList<String>(), plan.moveLeft)
        assertEquals(emptySet<String>(), plan.remember)
    }

    @Test
    fun `moves nothing twice`() {
        val plan = RightBarPlan.plan(listOf(left("Database")), remembered = setOf("Database"), clearRight = true)

        assertEquals(emptyList<String>(), plan.moveLeft)
        assertEquals(setOf("Database"), plan.remember)
    }

    @Test
    fun `puts back exactly what it moved`() {
        val windows = listOf(left("Database"), left("Project"))

        val plan = RightBarPlan.plan(windows, remembered = setOf("Database"), clearRight = false)

        assertEquals(listOf("Database"), plan.moveBack)
        assertEquals(emptyList<String>(), plan.moveLeft)
        assertEquals(emptySet<String>(), plan.remember)
    }

    /** The point of persisting the record: a window that was on the left before Agenstorm arrived stays there. */
    @Test
    fun `never moves a window it did not move`() {
        val plan = RightBarPlan.plan(listOf(left("Project")), remembered = emptySet(), clearRight = false)

        assertEquals(emptyList<String>(), plan.moveBack)
    }

    /** Moved by Agenstorm, then docked to the bottom by the user: theirs now, and not shoved to the right later. */
    @Test
    fun `stops owing a window the user moved somewhere of their own choosing`() {
        val windows = listOf(at("Database", ToolWindowAnchor.BOTTOM))

        val plan = RightBarPlan.plan(windows, remembered = setOf("Database"), clearRight = false)

        assertEquals(emptyList<String>(), plan.moveBack)
        assertEquals(emptySet<String>(), plan.remember)
    }

    @Test
    fun `forgets a window that no longer exists`() {
        val planOff = RightBarPlan.plan(listOf(left("Project")), remembered = setOf("Gone"), clearRight = false)
        assertEquals(emptyList<String>(), planOff.moveBack)

        val planOn = RightBarPlan.plan(listOf(left("Project")), remembered = setOf("Gone"), clearRight = true)
        assertEquals(emptySet<String>(), planOn.remember)
    }

    /** Dragged back onto the right while the feature is on: moved off again, and still owed. */
    @Test
    fun `moves back off a window the user dragged to the right`() {
        val plan = RightBarPlan.plan(listOf(right("Database")), remembered = setOf("Database"), clearRight = true)

        assertEquals(listOf("Database"), plan.moveLeft)
        assertEquals(setOf("Database"), plan.remember)
    }

    @Test
    fun `a window registered later is moved on the next pass`() {
        val plan = RightBarPlan.plan(
            listOf(left("Database"), right("AIAssistant")),
            remembered = setOf("Database"),
            clearRight = true,
        )

        assertEquals(listOf("AIAssistant"), plan.moveLeft)
        assertEquals(setOf("AIAssistant", "Database"), plan.remember)
    }
}
