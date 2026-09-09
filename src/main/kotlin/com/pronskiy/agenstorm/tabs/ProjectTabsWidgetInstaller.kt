package com.pronskiy.agenstorm.tabs

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.ui.customization.CustomActionsListener
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.diagnostic.logger
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step E1.3. Owns the `main.toolbar.Project` slot: while the feature is on, the stock project widget is
 * swapped for [ProjectTabsWidgetAction] and the instance taken out is kept, so switching the feature off puts
 * the platform's own widget back, untouched and behaving exactly as before.
 *
 * The swap is what the plugin used to get from `<action overrides="true">`, which forced this class to extend
 * the stock widget — `ProjectToolbarWidgetAction`, `@ApiStatus.Internal` — just to have something to delegate
 * to while the feature was off. `ActionManager.replaceAction` is public API, keeps the action's place in the
 * toolbar group, and never names the stock class: what comes out of `getAction` goes back in as a plain
 * [AnAction].
 *
 * Fails soft: an IDE whose main toolbar has no project slot only gets a warning, and the feature stays off.
 */
object ProjectTabsWidgetInstaller {

    const val PROJECT_WIDGET_ACTION_ID = "main.toolbar.Project"

    private val LOG = logger<ProjectTabsWidgetInstaller>()

    /** The platform's own widget while ours is in the slot; null whenever the slot is the platform's again. */
    private var stockAction: AnAction? = null

    /** Puts the slot in the state the setting asks for. Idempotent, so every entry point can just call it. */
    @Synchronized
    fun sync() {
        val enabled = AgenstormSettings.getInstance().state.projectTabsEnabled
        if (enabled) install() else uninstall()
    }

    @Synchronized
    fun install() {
        val actionManager = ActionManager.getInstance()
        val current = actionManager.getAction(PROJECT_WIDGET_ACTION_ID)
        if (current == null) {
            LOG.warn("No $PROJECT_WIDGET_ACTION_ID action in this IDE; the project tabs stay off")
            return
        }
        if (current is ProjectTabsWidgetAction) return
        stockAction = current
        actionManager.replaceAction(PROJECT_WIDGET_ACTION_ID, ProjectTabsWidgetAction())
        toolbarsChanged()
    }

    /** Gives the slot back to the widget taken out of it. A slot we never took stays as it is. */
    @Synchronized
    fun uninstall() {
        val stock = stockAction ?: return
        val actionManager = ActionManager.getInstance()
        if (actionManager.getAction(PROJECT_WIDGET_ACTION_ID) !is ProjectTabsWidgetAction) {
            stockAction = null
            return
        }
        actionManager.replaceAction(PROJECT_WIDGET_ACTION_ID, stock)
        stockAction = null
        toolbarsChanged()
    }

    /** Toolbars re-read their group on the next update; this is the platform's own nudge to do it now. */
    private fun toolbarsChanged() {
        CustomActionsListener.fireSchemaChanged()
    }
}

/** Takes the slot before the first frame's toolbar is built (declared under `applicationListeners`). */
class ProjectTabsWidgetLifecycleListener : AppLifecycleListener {

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        ProjectTabsWidgetInstaller.sync()
    }
}

/**
 * Gives the slot back before Agenstorm is unloaded: the swap happens at runtime, so nothing else would undo it
 * and the toolbar would lose its project widget until the next restart.
 */
class ProjectTabsWidgetUnloadListener : DynamicPluginListener {

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString == PLUGIN_ID) ProjectTabsWidgetInstaller.uninstall()
    }

    private companion object {
        const val PLUGIN_ID = "com.pronskiy.agenstorm"
    }
}
