package com.pronskiy.agenstorm.toolwindows

import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.impl.ToolWindowManagerImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step N1.3, and a deliberately small one.
 *
 * **Anchors cannot be exercised headlessly.** `BasePlatformTestCase` installs `ToolWindowHeadlessManagerImpl`, and it
 * is a stub in every way this feature cares about: a window registered as `RIGHT` or `LEFT` reports its anchor as
 * `bottom`, `setAnchor` does nothing, and `toolWindowIds` does not even list what was registered. Asserting on anchors
 * here would pin the stub, not the feature — it would pass with the production code deleted.
 *
 * So the decision lives in [RightBarPlanTest], where it is testable in full, and what is left here is the little that
 * is honest against the stub: the service survives a manager it can get nothing out of, and the listener watches the
 * events it must. The rest is an Epic N guardrail against a running IDE.
 */
class RightBarHiderTest : BasePlatformTestCase() {

    private val manager get() = ToolWindowManager.getInstance(project)
    private val record get() = MovedToolWindows.getInstance(project)
    private val hider get() = RightBarHider.getInstance(project)

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
            record.ids = emptySet()
        } finally {
            super.tearDown()
        }
    }

    /** Pins the reason the rest of this class is so thin, so a future platform change is noticed rather than assumed. */
    fun testTheFixtureHasNoUsableToolWindowManager() {
        assertFalse(
            "The fixture now has a real manager — the guardrail assertions belong back in this class",
            manager is ToolWindowManagerImpl,
        )
        manager.registerToolWindow(RegisterToolWindowTask.notClosable("${PREFIX}Probe", ToolWindowAnchor.RIGHT))
        assertEquals(
            "The stub no longer flattens every anchor to bottom — re-check what this class can assert",
            ToolWindowAnchor.BOTTOM,
            manager.getToolWindow("${PREFIX}Probe")?.anchor,
        )
    }

    fun testApplyChangesNothingAndThrowsNothingAgainstTheStub() {
        AgenstormSettings.getInstance().state.hideRightToolWindowBar = true

        hider.apply()

        assertTrue("Nothing was moved, so nothing may be recorded as owed", record.ids.isEmpty())
    }

    fun testRestoreAllThrowsNothingAgainstTheStub() {
        hider.restoreAll()

        assertTrue(record.ids.isEmpty())
    }

    /**
     * `SetToolWindowAnchor` must be watched — it is what a user's drag onto the right side raises — and the re-entry
     * our own `setAnchor` causes is stopped by the service's guard instead of by not listening.
     */
    fun testTheListenerWatchesTheEventsThatCouldBringTheBarBack() {
        val names = RightBarToolWindowListener.WATCHED.map { it.name }.toSet()

        assertTrue(names.contains("SetToolWindowAnchor"))
        assertTrue(names.contains("RegisterToolWindow"))
        assertTrue(names.contains("ToolWindowAvailable"))
        assertFalse("Watching a show/hide event would fight the user every time a window opens", names.contains("ShowToolWindow"))
    }

    private companion object {
        const val PREFIX = "AgenstormTest"
    }
}
