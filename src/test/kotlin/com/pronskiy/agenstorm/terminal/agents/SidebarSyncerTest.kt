package com.pronskiy.agenstorm.terminal.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step X1.4: the window in front records, every other window follows, and a width is stretched once. */
class SidebarSyncerTest {

    /** A tool window that lays out only when [layOut] says so, like Swing after `stretchWidth`. */
    private class FakeWindow(var visible: Boolean = false, var width: Int = 0, var anchor: String = "left", override var available: Boolean = true, var split: Boolean = true) : SidebarWindow {
        val steps = mutableListOf<SidebarStep>()
        private var pendingDelta = 0

        override fun layout() = FrameLayout(visible, width, anchor, split)

        override fun run(step: SidebarStep) {
            steps += step
            when (step) {
                is SidebarStep.SetAnchor -> anchor = step.anchor
                is SidebarStep.SetSplit -> split = step.split
                SidebarStep.Show -> { visible = true; if (width == 0) width = 456 }
                SidebarStep.Hide -> visible = false
                is SidebarStep.Stretch -> pendingDelta += step.delta
            }
        }

        fun layOut() {
            width += pendingDelta
            pendingDelta = 0
        }
    }

    private val state = AgentsSidebarState()
    private val windows = linkedMapOf("alpha" to FakeWindow(), "beta" to FakeWindow())
    private var front: String? = "alpha"
    private var enabled = true
    private val queue = ArrayDeque<() -> Unit>()

    private val syncer = SidebarSyncer(
        state = state,
        windows = { windows[it] },
        front = { front },
        all = { windows.keys.toList() },
        enabled = { enabled },
        later = { _, action -> queue.addLast(action) },
    ).also { syncer -> windows.keys.forEach(syncer::apply) }

    init {
        // The width checks every first apply queues, as the event queue would run them.
        runLater()
    }

    /** One turn of the event queue: what was queued so far, not what those actions queue again. */
    private fun runLater() {
        repeat(queue.size) { queue.removeFirst()() }
    }

    @Test
    fun theWindowInFrontIsCopiedToTheOthers() {
        windows.getValue("alpha").apply { visible = true; width = 576; anchor = "right" }

        syncer.record("alpha")
        runLater()
        windows.getValue("beta").layOut()

        val beta = windows.getValue("beta")
        assertEquals(FrameLayout(visible = true, width = 576, anchor = "right"), beta.layout())
        assertEquals(listOf(SidebarStep.SetAnchor("right"), SidebarStep.Show, SidebarStep.Stretch(120)), beta.steps)
    }

    @Test
    fun aWindowBehindRecordsNothing() {
        windows.getValue("beta").apply { visible = true; width = 300 }

        syncer.record("beta")
        runLater()

        assertEquals(AgentsSidebarState.Layout(), state.layout.value)
        assertTrue(windows.getValue("alpha").steps.isEmpty())
    }

    @Test
    fun theSettingOffRecordsAndAppliesNothing() {
        enabled = false
        windows.getValue("alpha").apply { visible = true; width = 576 }

        syncer.record("alpha")
        syncer.apply("beta")
        runLater()

        assertFalse(state.layout.value.visible)
        assertTrue(windows.getValue("beta").steps.isEmpty())
    }

    @Test
    fun anUnavailableWindowIsLeftAlone() {
        windows.getValue("beta").available = false
        windows.getValue("alpha").apply { visible = true; width = 576 }

        syncer.record("alpha")
        runLater()

        assertTrue(windows.getValue("beta").steps.isEmpty())
    }

    @Test
    fun aStretchNotLaidOutYetIsNotRepeated() {
        val beta = windows.getValue("beta").apply { visible = true; width = 456 }
        windows.getValue("alpha").apply { visible = true; width = 576 }

        syncer.record("alpha")
        runLater()
        assertEquals(listOf(SidebarStep.Stretch(120)), beta.steps)

        windows.getValue("alpha").width = 600
        syncer.record("alpha")
        runLater()
        assertEquals("still at the width it was stretched from: wait, do not stretch again", listOf(SidebarStep.Stretch(120)), beta.steps)

        beta.layOut()
        syncer.apply("beta")
        runLater()
        beta.layOut()
        assertEquals(600, beta.width)
        assertEquals(listOf(SidebarStep.Stretch(120), SidebarStep.Stretch(24)), beta.steps)
    }

    @Test
    fun aWindowThatOpensTakesTheSharedLayout() {
        state.update { it.copy(visible = true, width = 500, anchor = "left") }
        val gamma = FakeWindow()
        windows["gamma"] = gamma

        syncer.apply("gamma")
        runLater()
        gamma.layOut()

        assertEquals(FrameLayout(visible = true, width = 500, anchor = "left"), gamma.layout())
    }

    @Test
    fun closingInFrontClosesEverywhereAndKeepsTheWidth() {
        windows.values.forEach { it.visible = true; it.width = 576 }
        syncer.record("alpha")
        windows.getValue("alpha").visible = false

        syncer.record("alpha")
        runLater()

        assertFalse(windows.getValue("beta").visible)
        assertEquals(576, state.layout.value.width)
    }

    @Test
    fun aWindowRecordsOnlyOnceItTookTheSharedLayout() {
        state.update { it.copy(visible = true, width = 576, anchor = "left") }
        val gamma = FakeWindow(visible = false)
        windows["gamma"] = gamma
        front = "gamma"

        syncer.record("gamma")
        assertTrue("its own closed sidebar is not everyone's", state.layout.value.visible)

        syncer.apply("gamma")
        runLater()
        gamma.layOut()
        gamma.visible = false
        syncer.record("gamma")
        assertFalse("closed after it took the layout: that is the user's", state.layout.value.visible)
    }

    @Test
    fun aWindowMadeAvailableAgainWaitsForTheLayout() {
        windows.getValue("alpha").apply { visible = true; width = 576 }
        syncer.record("alpha")
        runLater()

        syncer.unsync("alpha")
        windows.getValue("alpha").visible = false
        syncer.record("alpha")
        assertTrue(state.layout.value.visible)

        syncer.apply("alpha")
        assertTrue(windows.getValue("alpha").visible)
    }

    @Test
    fun aChangeTheUserMadeInFrontIsRecorded() {
        windows.getValue("alpha").apply { visible = true; width = 576 }

        syncer.changed("alpha", byUser = true)

        assertTrue(state.layout.value.visible)
        assertTrue(windows.getValue("beta").visible)
    }

    @Test
    fun aChangeNobodyMadeIsUndone() {
        state.update { it.copy(visible = true, width = 576, anchor = "left") }
        windows.keys.forEach(syncer::apply)
        runLater()
        windows.values.forEach(FakeWindow::layOut)
        windows.getValue("alpha").visible = false

        syncer.changed("alpha", byUser = false)

        assertTrue("the platform closed it, the shared layout reopens it", windows.getValue("alpha").visible)
        assertTrue(state.layout.value.visible)
    }

    @Test
    fun aClickBehindIsNotTheUsersSidebar() {
        state.update { it.copy(visible = true, width = 576, anchor = "left") }
        windows.keys.forEach(syncer::apply)
        windows.getValue("beta").visible = false

        syncer.changed("beta", byUser = true)

        assertTrue(windows.getValue("beta").visible)
        assertTrue(state.layout.value.visible)
    }

    /** Every window at the shared 576 px, all checks run. */
    private fun settled() {
        state.update { it.copy(visible = true, width = 576, anchor = "left") }
        windows.values.forEach { it.visible = true; it.width = 576 }
        windows.keys.forEach(syncer::apply)
        repeat(10) { runLater() }
    }

    @Test
    fun aWidthTheWindowInFrontSettlesAtIsTheUsers() {
        settled()
        windows.getValue("alpha").width = 700

        syncer.changed("alpha", byUser = false)
        runLater()
        windows.getValue("beta").layOut()

        assertEquals(700, state.layout.value.width)
        assertEquals(700, windows.getValue("beta").width)
    }

    @Test
    fun aWidthOnItsWayIsNotTheUsers() {
        settled()
        syncer.apply("alpha")
        windows.getValue("alpha").width = 456

        syncer.changed("alpha", byUser = false)

        assertEquals("our own stretch is still pending there", 576, state.layout.value.width)
    }

    @Test
    fun aWidthBehindIsPutBack() {
        settled()
        windows.getValue("beta").width = 456

        syncer.changed("beta", byUser = false)
        runLater()
        windows.getValue("beta").layOut()

        assertEquals(576, windows.getValue("beta").width)
        assertEquals(576, state.layout.value.width)
    }

    @Test
    fun movingToTheUpperPartIsTheUsersOnlyOnInput() {
        settled()
        windows.getValue("alpha").split = false

        syncer.changed("alpha", byUser = false)
        assertTrue("the platform's: put back", windows.getValue("alpha").split)

        windows.getValue("alpha").split = false
        syncer.changed("alpha", byUser = true)
        assertFalse(state.layout.value.split)
        assertFalse(windows.getValue("beta").split)
    }
}
