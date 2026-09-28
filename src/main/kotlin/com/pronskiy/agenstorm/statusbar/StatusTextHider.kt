package com.pronskiy.agenstorm.statusbar

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.WindowManager
import com.pronskiy.agenstorm.core.AgenstormPlugin
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import java.awt.Component
import java.awt.Container

/**
 * Epic S. The status bar's status text — the last message, "Indexing…", a notification's title — is not shown.
 *
 * In the New UI that text and the bottom navigation bar share one slot: `InfoAndProgressPanel` shows the status text
 * whenever `UISettings.showNavigationBarInBottom` is false, and the platform's own "Status Text" item
 * (`StatusTextModeAction`, `@ApiStatus.Internal`) is nothing but `showNavigationBar = !state`. There is no switch for
 * the text alone, and the branch widget (E3) hides the navigation bar to take its place — so with it on, the text was
 * always there, beside the branch. Roman: "I also want status text to be off by default in all projects", and of the
 * choices, the branch alone in the bottom-left.
 *
 * The text lives in `com.intellij.openapi.wm.impl.status.StatusPanel`, a package-private class in 262 and 263 that
 * never sets its own visibility; `InfoAndProgressPanel` creates it once and only moves it between containers when the
 * navigation bar setting changes. So it is found by its class name under the public `StatusBar.getComponent()` and
 * hidden with `setVisible(false)` — no platform class is referenced, and a status bar laid out differently is left as
 * it is. Progress indicators live beside it and stay.
 */
object StatusTextHider {

    private val LOG = logger<StatusTextHider>()

    /** The status text's component, named rather than referenced: the class is package-private. */
    const val STATUS_PANEL_CLASS = "com.intellij.openapi.wm.impl.status.StatusPanel"

    /** On the EDT, after the current event, also while the settings dialog is open. */
    fun applyLater(project: Project) {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) sync(project, hide = AgenstormSettings.getInstance().state.statusBarHideStatusText)
        }, ModalityState.any())
    }

    /** EDT. Hides or shows [project]'s status text; returns whether its component was found. */
    fun sync(project: Project, hide: Boolean): Boolean {
        val root = WindowManager.getInstance().getStatusBar(project)?.component ?: return false
        val found = setShown(root, show = !hide)
        if (!found && LOG.isDebugEnabled) LOG.debug("status text: no $STATUS_PANEL_CLASS under the status bar")
        return found
    }

    /** Sets the visibility of every status-text component under [root]. Returns whether there was one. */
    fun setShown(root: Component, show: Boolean, className: String = STATUS_PANEL_CLASS): Boolean {
        val panels = findByClassName(root, className)
        for (panel in panels) {
            if (panel.isVisible != show) {
                panel.isVisible = show
                panel.parent?.let {
                    it.revalidate()
                    it.repaint()
                }
            }
        }
        return panels.isNotEmpty()
    }

    /** Every component under [root], [root] included, whose class is [className]. */
    fun findByClassName(root: Component, className: String): List<Component> {
        val found = mutableListOf<Component>()
        fun walk(c: Component) {
            if (c.javaClass.name == className) found += c
            if (c is Container) for (child in c.components) walk(child)
        }
        walk(root)
        return found
    }
}

/** Registered in `plugin.xml`: follows the setting when a project opens and whenever the settings page applies. */
class StatusTextStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().messageBus.connect(project)
            .subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { StatusTextHider.applyLater(project) })
        StatusTextHider.applyLater(project)
    }
}

/** Registered in `plugin.xml`: the status text comes back before the plugin's classes go. */
class StatusTextUnloadListener : DynamicPluginListener {

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (!AgenstormPlugin.isOurs(pluginDescriptor)) return
        for (project in ProjectManager.getInstance().openProjects) {
            if (!project.isDisposed) StatusTextHider.sync(project, hide = false)
        }
    }
}
