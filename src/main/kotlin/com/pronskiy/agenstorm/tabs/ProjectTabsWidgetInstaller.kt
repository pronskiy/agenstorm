package com.pronskiy.agenstorm.tabs

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.pronskiy.agenstorm.core.ActionSlot
import com.pronskiy.agenstorm.core.AgenstormPlugin
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step E1.3. Holds the `main.toolbar.Project` slot while the feature is on: the stock project widget is swapped
 * for [ProjectTabsWidgetAction] and kept, so switching the feature off puts the platform's own widget back,
 * untouched and behaving exactly as before. See [ActionSlot] for why this is not `<action overrides="true">`.
 */
object ProjectTabsWidgetInstaller {

    const val PROJECT_WIDGET_ACTION_ID = "main.toolbar.Project"

    private val slot = ActionSlot(PROJECT_WIDGET_ACTION_ID) { ProjectTabsWidgetAction() }

    fun sync() {
        slot.sync(AgenstormSettings.getInstance().state.projectTabsEnabled)
    }

    fun install() = slot.take()

    fun uninstall() = slot.giveBack()

    fun isInstalled(): Boolean = slot.isTaken()
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
        if (AgenstormPlugin.isOurs(pluginDescriptor)) ProjectTabsWidgetInstaller.uninstall()
    }
}
