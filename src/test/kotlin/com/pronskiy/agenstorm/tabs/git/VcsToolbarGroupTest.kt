package com.pronskiy.agenstorm.tabs.git

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/** Step E3.1: the toolbar's VCS group is ours, keeps the stock children, and hides while the branch is in the status bar. */
class VcsToolbarGroupTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
    }

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testOverridesTheStockGroupAndStillListsTheBranchWidget() {
        val group = ActionManager.getInstance().getAction(VcsToolbarGroup.ID)
        assertTrue("$group", group is VcsToolbarGroup)
        val ids = (group as VcsToolbarGroup).getChildren(null).map { ActionManager.getInstance().getId(it) }
        assertTrue("children: $ids", "main.toolbar.git.Branches" in ids)
    }

    fun testHiddenWhileTheBranchLivesInTheStatusBarAndVisibleOtherwise() {
        val group = VcsToolbarGroup()
        val event = TestActionEvent.createTestEvent(group, SimpleDataContext.getProjectContext(project))

        group.update(event)
        assertFalse(event.presentation.isVisible)

        AgenstormSettings.getInstance().state.branchInStatusBar = false
        group.update(event)
        assertTrue(event.presentation.isVisible)

        AgenstormSettings.getInstance().state.branchInStatusBar = true
        AgenstormSettings.getInstance().state.projectTabsEnabled = false
        group.update(event)
        assertTrue("tabs off means the stock toolbar", event.presentation.isVisible)
    }
}
