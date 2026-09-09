package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.tabs.ui.ProjectTabsPanel

/**
 * Step E1.3: the tab strip takes the main toolbar's project slot while the feature is on, and the platform's
 * own widget gets the slot back when it is off.
 */
class ProjectTabsWidgetActionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        ProjectTabsWidgetInstaller.uninstall()
    }

    override fun tearDown() {
        try {
            ProjectTabsWidgetInstaller.uninstall()
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private fun projectWidgetAction() =
        ActionManager.getInstance().getAction(ProjectTabsWidgetInstaller.PROJECT_WIDGET_ACTION_ID)

    fun testInstallTakesTheSlotAndUninstallGivesItBack() {
        val stock = projectWidgetAction()
        assertNotNull("this IDE has no main.toolbar.Project action", stock)
        assertFalse(stock is ProjectTabsWidgetAction)

        ProjectTabsWidgetInstaller.install()
        assertTrue(projectWidgetAction() is ProjectTabsWidgetAction)

        // Idempotent: a second install must not remember our own action as the one to restore.
        ProjectTabsWidgetInstaller.install()
        ProjectTabsWidgetInstaller.uninstall()
        assertSame(stock, projectWidgetAction())
    }

    fun testSyncFollowsTheFeatureToggle() {
        ProjectTabsWidgetInstaller.sync()
        assertTrue(projectWidgetAction() is ProjectTabsWidgetAction)

        AgenstormSettings.getInstance().state.projectTabsEnabled = false
        ProjectTabsWidgetInstaller.sync()
        assertFalse(projectWidgetAction() is ProjectTabsWidgetAction)

        AgenstormSettings.getInstance().state.projectTabsEnabled = true
        ProjectTabsWidgetInstaller.sync()
        assertTrue(projectWidgetAction() is ProjectTabsWidgetAction)
    }

    fun testUpdatePublishesTheFrameProject() {
        val action = ProjectTabsWidgetAction()
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))

        action.update(event)

        assertSame(project, event.presentation.getClientProperty(ProjectTabsWidgetAction.PROJECT_KEY))
    }

    fun testComponentIsTheTabStripBoundToTheFrameProject() {
        val action = ProjectTabsWidgetAction()
        val presentation = Presentation()

        val component = action.createCustomComponent(presentation, ActionPlaces.MAIN_TOOLBAR) as ProjectTabsPanel
        assertNull(component.ownerProject)

        presentation.putClientProperty(ProjectTabsWidgetAction.PROJECT_KEY, project)
        action.updateCustomComponent(component, presentation)

        assertSame(project, component.ownerProject)
    }
}
