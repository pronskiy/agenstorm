package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.tabs.ui.SwitchingPanel

/** Step E1.3: the stock project widget is overridden and the slot switches between stock widget and tab strip. */
class ProjectTabsWidgetActionTest : BasePlatformTestCase() {

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

    fun testOverridesTheStockProjectWidgetAction() {
        val action = ActionManager.getInstance().getAction("main.toolbar.Project")
        assertTrue("main.toolbar.Project is $action", action is ProjectTabsWidgetAction)
    }

    fun testUpdatePublishesTheFrameProjectAndTheToggle() {
        val action = ProjectTabsWidgetAction()
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))

        action.update(event)
        assertSame(project, event.presentation.getClientProperty(ProjectTabsWidgetAction.PROJECT_KEY))
        assertEquals(true, event.presentation.getClientProperty(ProjectTabsWidgetAction.TABS_MODE_KEY))

        AgenstormSettings.getInstance().state.projectTabsEnabled = false
        action.update(event)
        assertEquals(false, event.presentation.getClientProperty(ProjectTabsWidgetAction.TABS_MODE_KEY))
    }

    fun testComponentShowsTheStockWidgetUntilTabsModeIsOnAndBack() {
        val action = ProjectTabsWidgetAction()
        val presentation = Presentation()
        val component = action.createCustomComponent(presentation, ActionPlaces.MAIN_TOOLBAR) as SwitchingPanel
        assertFalse(component.isShowingTabs)
        assertTrue(component.stock.isVisible)

        presentation.putClientProperty(ProjectTabsWidgetAction.TABS_MODE_KEY, true)
        presentation.putClientProperty(ProjectTabsWidgetAction.PROJECT_KEY, project)
        action.updateCustomComponent(component, presentation)
        assertTrue(component.isShowingTabs)
        assertTrue(component.tabs.isVisible)
        assertFalse(component.stock.isVisible)
        assertSame(project, component.tabs.ownerProject)
        assertEquals(component.tabs.preferredSize, component.preferredSize)

        presentation.putClientProperty(ProjectTabsWidgetAction.TABS_MODE_KEY, false)
        action.updateCustomComponent(component, presentation)
        assertFalse(component.isShowingTabs)
        assertTrue(component.stock.isVisible)
        assertEquals(component.stock.preferredSize, component.preferredSize)
    }
}
