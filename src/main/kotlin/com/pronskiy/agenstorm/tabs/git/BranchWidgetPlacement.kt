package com.pronskiy.agenstorm.tabs.git

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.ide.ui.NavBarLocation
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.impl.status.IdeStatusBarImpl
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import javax.swing.JComponent

/**
 * Step E3.3. Puts [BranchStatusBarWidget] where the breadcrumbs were: hides the bottom navigation bar (and
 * remembers that it was us, so turning the feature off restores it while a user's own choice is left alone),
 * and moves the widget's component into the status bar's central slot. The move happens when the status bar
 * installs the widget ([placeCentrally] from `install`), not from here, because the widgets manager creates
 * widgets asynchronously. `IdeStatusBarImpl.setCentralWidget` is internal API (SPEC.md §2): when it is missing
 * or the status bar is another implementation, the widget stays where the platform placed it (first of the
 * ordinary widgets) and a warning is logged once.
 */
object BranchWidgetPlacement {

    private val LOG = logger<BranchWidgetPlacement>()
    const val CENTRAL_KEY = "agenstorm.branch.central"
    private var centralPlacementBroken = false

    enum class NavBarChange { NONE, HIDDEN, RESTORED }

    /** Re-applies the placement for every open project after the settings changed. */
    fun applyToAllProjects() {
        for (project in ProjectManager.getInstance().openProjects) apply(project)
    }

    /** Runs on the EDT (after the current event, so a settings change has propagated) for [project]. */
    fun apply(project: Project) {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) applyNow(project) }, ModalityState.any())
    }

    fun applyNow(project: Project) {
        val on = BranchStatusBarWidgetFactory.isFeatureOn()
        syncNavBar(on, AgenstormSettings.getInstance().state, UISettings.getInstance())
        // Creates the widget when it became available, removes it when it did not; install()/dispose() do the placing.
        project.getService(StatusBarWidgetsManager::class.java).updateWidget(BranchStatusBarWidgetFactory::class.java)
    }

    /** Moves [component] into the left slot; a no-op on status bars without that slot. */
    fun placeCentrally(statusBar: StatusBar, component: JComponent) {
        if (centralPlacementBroken) return
        try {
            val impl = statusBar as? IdeStatusBarImpl ?: return
            impl.setCentralWidget(CENTRAL_KEY, component)
        } catch (e: LinkageError) {
            centralPlacementBroken = true
            LOG.warn("Cannot place the branch widget in the status bar's left slot; leaving it with the other widgets", e)
        }
    }

    /** Frees the left slot and takes the component out of whatever container holds it. */
    fun clearCentral(statusBar: StatusBar, component: JComponent) {
        component.parent?.remove(component)
        if (centralPlacementBroken) return
        try {
            (statusBar as? IdeStatusBarImpl)?.setCentralWidget(CENTRAL_KEY, null)
        } catch (e: LinkageError) {
            centralPlacementBroken = true
        }
    }

    /**
     * After a UI settings change: with the navigation bar hidden, make sure the widget still owns the left slot
     * (the platform clears it when it removes its own bar); with the bar back, the platform has taken the slot,
     * so recreate the widget among the ordinary ones.
     */
    fun onUiSettingsChanged(project: Project, statusBar: StatusBar, widget: BranchStatusBarWidget) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || statusBar.getWidget(BranchStatusBarWidgetFactory.ID) !== widget) return@invokeLater
            val ui = UISettings.getInstance()
            val navBarAtBottom = ui.showNavigationBar && ui.navBarLocation == NavBarLocation.BOTTOM
            if (!navBarAtBottom) {
                placeCentrally(statusBar, widget.component)
            } else if (widget.component.parent == null) {
                statusBar.removeWidget(BranchStatusBarWidgetFactory.ID)
                project.getService(StatusBarWidgetsManager::class.java).updateWidget(BranchStatusBarWidgetFactory::class.java)
            }
        }, ModalityState.any())
    }

    /**
     * Feature on: hide a bottom navigation bar and note that we did. Feature off: bring it back only if we hid
     * it. Returns what changed.
     */
    fun syncNavBar(on: Boolean, state: AgenstormSettings.State, ui: UISettings): NavBarChange {
        if (on) {
            if (!ui.showNavigationBar || ui.navBarLocation != NavBarLocation.BOTTOM) return NavBarChange.NONE
            ui.showNavigationBar = false
            state.navBarHiddenByAgenstorm = true
            ui.fireUISettingsChanged()
            return NavBarChange.HIDDEN
        }
        if (!state.navBarHiddenByAgenstorm) return NavBarChange.NONE
        state.navBarHiddenByAgenstorm = false
        if (ui.showNavigationBar) return NavBarChange.NONE
        ui.showNavigationBar = true
        ui.fireUISettingsChanged()
        return NavBarChange.RESTORED
    }

}

/** Step E3.3: places the widget when a project opens, when its repositories appear, and when the settings change. */
class BranchStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val projectBus = project.messageBus.connect()
        projectBus.subscribe(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED, VcsRepositoryMappingListener { BranchWidgetPlacement.apply(project) })
        ApplicationManager.getApplication().messageBus.connect(project).subscribe(
            AgenstormSettingsListener.TOPIC,
            AgenstormSettingsListener { BranchWidgetPlacement.apply(project) },
        )
        BranchWidgetPlacement.apply(project)
    }
}
