package com.pronskiy.agenstorm.toolwindows

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.wm.ex.ToolWindowManagerListener.ToolWindowManagerEventType
import com.pronskiy.agenstorm.core.AgenstormPlugin
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener

/**
 * Epic N. Empties the right tool window bar by moving its tool windows to the left, which is what makes the platform
 * hide the bar.
 *
 * The bar is `ToolWindowRightToolbar` and never touched — it does not need to be.
 * `ToolWindowPaneNewButtonManager.updateToolStripesVisibility` runs
 * `right.setVisible(showButtons && right.hasVisibleButtons())`, and `hasVisibleButtons()` is
 * `hasButtons() || moreButton.isVisible`. A right stripe with no buttons hides itself, and the ⋯ More button does not
 * keep it alive: its `isAvailable` requires `ToolWindowManagerEx.getMoreButtonSide() == RIGHT`, and that side defaults
 * to the left.
 *
 * **Moving them is the only way there that is not internal API.** Emptying the stripe in place would mean
 * `ToolWindowManagerImpl.hideToolWindow(…, removeFromStripe = true)` and `setVisibleOnLargeStripe`, and the class and
 * both methods are `@ApiStatus.Internal` — `verifyPlugin` reported all three when that version was tried. The public
 * `ToolWindow.setShowStripeButton` is no help either: it is a no-op in the New UI. `setAnchor` is clean, and
 * `ToolWindowManagerImpl.doSetAnchor` has an explicit New UI branch, so it genuinely works there. Decision 48.
 *
 * The tool windows keep their content and every way in — View | Tool Windows, their own shortcuts, the ⋯ More button —
 * and their icons sit on the left bar instead. Which is also why opening one now opens it on the left.
 */
@Service(Service.Level.PROJECT)
class RightBarHider(private val project: Project) : Disposable {

    /** Guards the re-entry through [RightBarToolWindowListener]: our own `setAnchor` raises `SetToolWindowAnchor`. */
    private var applying = false

    /** Moves windows off the right side or puts them back, following the current setting. */
    fun apply() = run(AgenstormSettings.getInstance().state.hideRightToolWindowBar)

    /** Puts back every window Agenstorm moved, whatever the setting says. Feature-off and plugin unload. */
    fun restoreAll() = run(clearRight = false)

    /** [apply] on the EDT, also while a modal dialog (the settings page) is open. */
    fun applyLater() {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) apply() }, ModalityState.any())
    }

    private fun run(clearRight: Boolean) {
        if (project.isDisposed || applying) return
        val manager = ToolWindowManager.getInstance(project)
        val record = MovedToolWindows.getInstance(project)
        val windows = manager.toolWindowIds.mapNotNull { id ->
            val toolWindow = manager.getToolWindow(id) ?: return@mapNotNull null
            ToolWindowSide(id, toolWindow.anchor)
        }

        val plan = RightBarPlan.plan(windows, record.ids, clearRight)
        // Guarded rather than `LOG.debug { … }`: the lambda overload is an inline extension compiled against a
        // newer JVM target than this module's 21, which the compiler refuses.
        if (LOG.isDebugEnabled) {
            LOG.debug(
                "clearRight=$clearRight saw=" + windows.joinToString { "${it.id}@${it.anchor}" } +
                    " owed=${record.ids} moveLeft=${plan.moveLeft} moveBack=${plan.moveBack}",
            )
        }
        applying = true
        try {
            for (id in plan.moveLeft) manager.getToolWindow(id)?.setAnchor(ToolWindowAnchor.LEFT, null)
            for (id in plan.moveBack) manager.getToolWindow(id)?.setAnchor(ToolWindowAnchor.RIGHT, null)
        } finally {
            applying = false
        }
        record.ids = plan.remember
    }

    /**
     * Nothing to undo here. A project closing keeps its layout as it stands, and the windows are put back before that
     * could matter — by the settings page when the feature goes off, and by [RightBarUnloadListener] when the plugin
     * is unloaded, which runs while this service is still alive.
     */
    override fun dispose() = Unit

    companion object {
        private val LOG = logger<RightBarHider>()

        fun getInstance(project: Project): RightBarHider = project.service()
    }
}

/**
 * Subscribed by [RightBarStartupActivity] rather than registered in `plugin.xml`, because `stateChanged` hands over
 * the manager and not the project.
 *
 * Only the events that would otherwise let the bar come back are acted on: a tool window appearing for the first
 * time, becoming available, or being dragged onto the right side. The re-entry our own `setAnchor` causes is stopped
 * by [RightBarHider.applying] rather than by leaving `SetToolWindowAnchor` unwatched — that event is exactly the one
 * a user's drag raises, and it is the whole point of watching.
 */
class RightBarToolWindowListener(private val project: Project) : ToolWindowManagerListener {

    override fun stateChanged(toolWindowManager: ToolWindowManager, changeType: ToolWindowManagerEventType) {
        if (changeType !in WATCHED) return
        RightBarHider.getInstance(project).applyLater()
    }

    companion object {
        val WATCHED: Set<ToolWindowManagerEventType> = setOf(
            ToolWindowManagerEventType.RegisterToolWindow,
            ToolWindowManagerEventType.ToolWindowAvailable,
            ToolWindowManagerEventType.SetToolWindowAnchor,
            ToolWindowManagerEventType.SetSideToolAndAnchor,
        )
    }
}

/**
 * Registered in `plugin.xml`. Applies the setting to the tool windows this project already has, and again whenever the
 * settings page applies — the page lives in `core/` and must not know this feature, so it speaks through
 * [AgenstormSettingsListener].
 */
class RightBarStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val service = RightBarHider.getInstance(project)
        ApplicationManager.getApplication().messageBus.connect(service)
            .subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { service.applyLater() })
        project.messageBus.connect(service)
            .subscribe(ToolWindowManagerListener.TOPIC, RightBarToolWindowListener(project))
        service.applyLater()
    }
}

/**
 * Registered in `plugin.xml`. Puts every moved window back before the plugin's classes go, so uninstalling Agenstorm
 * does not leave a project with a layout nobody chose.
 */
class RightBarUnloadListener : DynamicPluginListener {

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (!AgenstormPlugin.isOurs(pluginDescriptor)) return
        for (project in ProjectManager.getInstance().openProjects) {
            if (!project.isDisposed) RightBarHider.getInstance(project).restoreAll()
        }
    }
}
