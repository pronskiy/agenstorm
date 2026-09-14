package com.pronskiy.agenstorm.projectview

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.pronskiy.agenstorm.core.ActionSlot
import com.pronskiy.agenstorm.core.AgenstormPlugin
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step O1.3. Holds the two creation slots while the feature is on, and gives them back when it is off.
 *
 * `NewFile` and `NewDir` are declared inside `<group id="NewGroup">` in the platform's `LangActions.xml`, which
 * is what the Alt+Insert popup, File | New and the project view's context menu all render — so taking the two
 * ids covers every way in at once, and `NewElement` itself, the popup around them, is left alone.
 *
 * `NewDir`'s stock action is `@ApiStatus.Internal`. That is exactly what [ActionSlot] is for: the displaced
 * action is held as a plain [AnAction] and its type is never named here.
 */
object ProjectViewActionInstaller {

    const val NEW_FILE_ACTION_ID: String = "NewFile"
    const val NEW_DIR_ACTION_ID: String = "NewDir"

    private val slots = listOf(
        ActionSlot(NEW_FILE_ACTION_ID) { InlineCreateAction(InlineNameKind.NEW_FILE, stockAction(NEW_FILE_ACTION_ID)) },
        ActionSlot(NEW_DIR_ACTION_ID) { InlineCreateAction(InlineNameKind.NEW_DIRECTORY, stockAction(NEW_DIR_ACTION_ID)) },
    )

    /** Whatever is in the slot right now, which is the platform's own until the slot is taken. */
    private fun stockAction(id: String): AnAction? = ActionManager.getInstance().getAction(id)

    fun sync() {
        val on = AgenstormSettings.getInstance().state.projectTreeInlineNamingEnabled
        slots.forEach { it.sync(on) }
    }

    fun install() = slots.forEach { it.take() }

    fun uninstall() = slots.forEach { it.giveBack() }

    fun isInstalled(): Boolean = slots.all { it.isTaken() }
}

/** Takes the slots as the IDE starts (declared under `applicationListeners`). */
class ProjectViewLifecycleListener : AppLifecycleListener {

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        ProjectViewActionInstaller.sync()
    }
}

/** Gives the slots back before Agenstorm is unloaded, so the IDE keeps its own New File and New Directory. */
class ProjectViewUnloadListener : DynamicPluginListener {

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (AgenstormPlugin.isOurs(pluginDescriptor)) ProjectViewActionInstaller.uninstall()
    }
}
