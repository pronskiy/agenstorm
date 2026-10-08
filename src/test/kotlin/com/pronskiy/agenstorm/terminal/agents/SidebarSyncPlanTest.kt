package com.pronskiy.agenstorm.terminal.agents

import com.pronskiy.agenstorm.terminal.agents.AgentsSidebarState.Layout
import com.pronskiy.agenstorm.terminal.agents.SidebarStep.Hide
import com.pronskiy.agenstorm.terminal.agents.SidebarStep.SetAnchor
import com.pronskiy.agenstorm.terminal.agents.SidebarStep.Show
import com.pronskiy.agenstorm.terminal.agents.SidebarStep.Stretch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Step X1.3: from the shared layout to one window's steps, and back from the window in front. */
class SidebarSyncPlanTest {

    private val shared = Layout(visible = true, width = 576, anchor = "left")

    @Test
    fun aWindowThatAlreadyMatchesNeedsNothing() {
        val current = FrameLayout(visible = true, width = 576, anchor = "left")

        assertEquals(emptyList<SidebarStep>(), SidebarSyncPlan.steps(shared, current))
        assertNull(SidebarSyncPlan.widthStep(shared, current))
    }

    @Test
    fun theSideComesFirstThenVisibility() {
        val current = FrameLayout(visible = false, width = 0, anchor = "right")

        assertEquals(listOf(SetAnchor("left"), Show), SidebarSyncPlan.steps(shared, current))
    }

    @Test
    fun aClosedSidebarClosesEverywhere() {
        val current = FrameLayout(visible = true, width = 576, anchor = "left")

        assertEquals(listOf(Hide), SidebarSyncPlan.steps(shared.copy(visible = false), current))
        assertNull("a closed sidebar is not resized", SidebarSyncPlan.widthStep(shared.copy(visible = false), current))
    }

    @Test
    fun theWidthIsOneDeltaFromTheLaidOutWidth() {
        assertEquals(Stretch(120), SidebarSyncPlan.widthStep(shared, FrameLayout(visible = true, width = 456, anchor = "left")))
        assertEquals(Stretch(-30), SidebarSyncPlan.widthStep(shared, FrameLayout(visible = true, width = 606, anchor = "left")))
    }

    @Test
    fun aPixelOrTwoIsTheSameWidth() {
        assertNull(SidebarSyncPlan.widthStep(shared, FrameLayout(visible = true, width = 574, anchor = "left")))
        assertEquals(Stretch(3), SidebarSyncPlan.widthStep(shared, FrameLayout(visible = true, width = 573, anchor = "left")))
    }

    @Test
    fun noWidthUntilTheWindowHasLaidOutOnTheRightSide() {
        assertNull("not shown yet", SidebarSyncPlan.widthStep(shared, FrameLayout(visible = false, width = 456, anchor = "left")))
        assertNull("never laid out", SidebarSyncPlan.widthStep(shared, FrameLayout(visible = true, width = 0, anchor = "left")))
        assertNull("still on the other side", SidebarSyncPlan.widthStep(shared, FrameLayout(visible = true, width = 456, anchor = "right")))
        assertNull("nothing recorded", SidebarSyncPlan.widthStep(Layout(visible = true), FrameLayout(visible = true, width = 456, anchor = "left")))
    }

    @Test
    fun nothingRecordedChangesNothing() {
        val current = FrameLayout(visible = false, width = 0, anchor = "left")

        assertEquals(emptyList<SidebarStep>(), SidebarSyncPlan.steps(Layout(), current))
    }

    @Test
    fun recordingTakesTheWindowInFront() {
        val recorded = SidebarSyncPlan.record(shared, FrameLayout(visible = true, width = 400, anchor = "right"))

        assertEquals(Layout(visible = true, width = 400, anchor = "right"), recorded)
    }

    @Test
    fun aClosedWindowKeepsTheWidthItHadOpen() {
        val recorded = SidebarSyncPlan.record(shared, FrameLayout(visible = false, width = 0, anchor = "left"))

        assertEquals(Layout(visible = false, width = 576, anchor = "left"), recorded)
    }

    @Test
    fun recordingKeepsTheTreeState() {
        val withTree = shared.copy(selected = "group:/w/app", collapsed = mutableListOf("/w/old"), scroll = 40)

        val recorded = SidebarSyncPlan.record(withTree, FrameLayout(visible = true, width = 500, anchor = "left"))

        assertEquals("group:/w/app", recorded.selected)
        assertEquals(listOf("/w/old"), recorded.collapsed)
        assertEquals(40, recorded.scroll)
    }
}
