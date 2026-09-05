package com.pronskiy.agenstorm.tabs.git

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.ide.ui.NavBarLocation
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.ui.components.JBLabel
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import java.awt.BorderLayout
import java.awt.Container
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Step E3.3. Puts [BranchStatusBarWidget] where the breadcrumbs were: hides the bottom navigation bar (and
 * remembers that it was us, so turning the feature off restores it while a user's own choice is left alone),
 * and attaches the widget's label to the status bar's left panel. The status bar (`StatusBar.getComponent()`,
 * public API) is a `BorderLayout` whose WEST slot holds the navigation bar's horizontal box; the label goes to
 * the front of that box, or into a box of our own when the platform has not created one. No internal API is
 * involved; if the layout is not what we expect, the label is shown inside the widget's own component among
 * the ordinary widgets instead.
 */
object BranchWidgetPlacement {

    enum class NavBarChange { NONE, HIDDEN, RESTORED }

    /** Where a label ended up, so [detach] can undo exactly that. */
    class Attachment(val container: Container, val ownWestPanel: JComponent?, val leftCorner: Boolean)

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
        // Creates the widget when it became available, removes it when it did not; the widget places itself.
        project.getService(StatusBarWidgetsManager::class.java).updateWidget(BranchStatusBarWidgetFactory::class.java)
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

    /**
     * Moves [label] to the front of the status bar's WEST box (creating the box when the platform has none) and
     * keeps [host] invisible; falls back to showing the label inside [host] when [bar] is not a `BorderLayout`.
     */
    fun attach(bar: JComponent?, host: JComponent, label: JComponent): Attachment {
        val layout = bar?.layout as? BorderLayout
        if (bar == null || layout == null) {
            move(label, host, index = -1)
            host.isVisible = true
            host.revalidate()
            return Attachment(host, ownWestPanel = null, leftCorner = false)
        }
        val existing = layout.getLayoutComponent(BorderLayout.WEST) as? JComponent
        var own: JComponent? = null
        val west = existing ?: JPanel().apply {
            this.layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            bar.add(this, BorderLayout.WEST)
            own = this
        }
        move(label, west, index = 0)
        west.isVisible = true
        host.isVisible = false
        bar.revalidate()
        bar.repaint()
        return Attachment(west, own, leftCorner = true)
    }

    /** Takes the label out again and removes a WEST box we created once it is empty. */
    fun detach(attachment: Attachment) {
        val container = attachment.container
        for (child in container.components.toList()) if (child is JBLabel) container.remove(child)
        val own = attachment.ownWestPanel
        if (own != null && own.componentCount == 0) own.parent?.remove(own)
        (container.parent ?: container).revalidate()
        (container.parent ?: container).repaint()
    }

    private fun move(component: JComponent, target: Container, index: Int) {
        if (component.parent === target) return
        component.parent?.remove(component)
        if (index < 0) target.add(component) else target.add(component, index)
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
