package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.WindowManager
import com.pronskiy.agenstorm.core.AgenstormSettings
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JRootPane

/**
 * Step E1.6. The macOS window tabs stay **on** — they are what merges every project window into one window
 * (`JdkEx.setTabbingMode`, gated by the same `ide.mac.os.wintabs.version2` key [NativeTabsRegistryGuard] guards).
 * What Agenstorm removes is only their row: `MacWinTabsHandlerV2` parks its `WindowTabsComponent` in a plain
 * `JPanel` stored on the frame's root pane under the client property [CONTAINER_KEY], and
 * `IdeRootPane.CustomHeaderRootLayout` gives that panel height **only while it is visible**. Hiding it therefore
 * collapses the row and leaves the header (traffic lights + main toolbar, laid out above it) untouched — the
 * same layout the platform itself produces whenever a tab group is down to one window.
 *
 * Nothing is re-parented, so `MacWinTabsHandlerV2`'s bookkeeping (which wants the panel to hold exactly one
 * child) is intact. The platform brings the row back in `createTabBarsForFrame`; a component listener per frame
 * puts it away again. When the feature is off we restore only what we hid, exactly like
 * [NativeTabsRegistryGuard] and `BranchWidgetPlacement` do with the settings they touch.
 *
 * Off macOS — and whenever tabbing mode is unavailable — the root pane has no such panel and everything here is
 * a no-op; the toolbar strip then works as a window switcher, which is all those platforms can offer.
 */
object NativeTabStrip {

    /** Client property `MacWinTabsHandlerV2` stores its tab row under, on the frame's root pane. */
    const val CONTAINER_KEY: String = "WINDOW_TABS_CONTAINER_KEY"

    /** Set on the row while Agenstorm is the one keeping it hidden. */
    private const val HIDDEN_BY_US = "agenstorm.tabs.rowHiddenByAgenstorm"

    private val LOG = logger<NativeTabStrip>()

    /** The platform's tab row of this root pane, or null when this IDE has no window tabs. */
    fun containerOf(rootPane: JRootPane?): JComponent? = rootPane?.getClientProperty(CONTAINER_KEY) as? JComponent

    /**
     * True when [project]'s window is part of a macOS tab group, i.e. it shares one window with the other
     * projects. The presence of the row is the platform's own answer to `JdkEx.isTabbingModeAvailable()`,
     * evaluated when the frame was built — cheaper and more honest than re-deriving it from the registry.
     */
    fun isTabbed(project: Project): Boolean =
        SystemInfo.isMac && containerOf(WindowManager.getInstance().getFrame(project)?.rootPane) != null

    /** Hides the row of [project]'s frame and keeps it hidden; no-op when this frame has no row. */
    fun install(project: Project) {
        val container = containerOf(WindowManager.getInstance().getFrame(project)?.rootPane)
        if (container == null) {
            LOG.debug("No macOS project tab row on this frame; the toolbar strip switches windows instead")
            return
        }
        val watcher = object : ComponentAdapter() {
            override fun componentShown(e: ComponentEvent) {
                applyTo(container)
            }
        }
        container.addComponentListener(watcher)
        Disposer.register(project, Disposable { container.removeComponentListener(watcher) })
        applyTo(container)
    }

    /** Re-applies the rule to every open project, e.g. after the tabs toggle changed. Posted to the EDT. */
    fun applyToAllProjects() {
        ApplicationManager.getApplication().invokeLater({
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                containerOf(WindowManager.getInstance().getFrame(project)?.rootPane)?.let(::applyTo)
            }
        }, ModalityState.any())
    }

    private fun applyTo(container: JComponent) {
        apply(container, AgenstormSettings.getInstance().state.projectTabsEnabled, ProjectTabsModel.getInstance().tabs().size)
    }

    /**
     * Feature on: the row goes away and is marked as ours. Feature off: only a row we hid comes back, and it
     * comes back the way the platform would have it — visible once a second window joined the group. Returns
     * whether the visibility changed.
     */
    fun apply(container: JComponent, featureOn: Boolean, tabCount: Int): Boolean {
        if (featureOn) {
            if (!container.isVisible) return false
            container.putClientProperty(HIDDEN_BY_US, true)
            return setVisible(container, false)
        }
        if (container.getClientProperty(HIDDEN_BY_US) != true) return false
        container.putClientProperty(HIDDEN_BY_US, null)
        return setVisible(container, tabCount > 1)
    }

    private fun setVisible(container: JComponent, visible: Boolean): Boolean {
        if (container.isVisible == visible) return false
        container.isVisible = visible
        container.rootPane?.let {
            it.revalidate()
            it.repaint()
        }
        return true
    }
}
