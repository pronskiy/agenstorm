package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.impl.headertoolbar.ProjectToolbarWidgetAction
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.tabs.ui.ProjectTabsPanel
import com.pronskiy.agenstorm.tabs.ui.SwitchingPanel
import javax.swing.JComponent

/**
 * Step E1.3. Replaces the stock project widget (`main.toolbar.Project`, registered with `overrides="true"`) in
 * the main toolbar. With the feature on, the slot shows [ProjectTabsPanel]: one tab per open project, the frame's
 * own project highlighted, plus a "+" button. With the feature off, the stock widget is shown and behaves exactly
 * as before, because this class extends it and forwards every call. `ProjectToolbarWidgetAction` is
 * `@ApiStatus.Internal`; SPEC.md §2 accepts that and E1.5 holds the fallback.
 */
class ProjectTabsWidgetAction : ProjectToolbarWidgetAction() {

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.putClientProperty(PROJECT_KEY, e.project)
        e.presentation.putClientProperty(TABS_MODE_KEY, AgenstormSettings.getInstance().state.projectTabsEnabled)
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
        SwitchingPanel(stock = super.createCustomComponent(presentation, place), tabs = ProjectTabsPanel().also(ProjectTabActions::wire))

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        val panel = component as? SwitchingPanel
        if (panel == null) {
            super.updateCustomComponent(component, presentation)
            return
        }
        val tabsMode = presentation.getClientProperty(TABS_MODE_KEY) == true
        panel.showTabs(tabsMode)
        panel.tabs.ownerProject = presentation.getClientProperty(PROJECT_KEY)
        if (!tabsMode) super.updateCustomComponent(panel.stock, presentation)
    }

    companion object {
        /** The project of the frame this toolbar belongs to; that tab is rendered as the active one. */
        val PROJECT_KEY: Key<Project> = Key.create("agenstorm.tabs.project")
        /** True when the tab strip should be shown instead of the stock widget. */
        val TABS_MODE_KEY: Key<Boolean> = Key.create("agenstorm.tabs.mode")
    }
}
