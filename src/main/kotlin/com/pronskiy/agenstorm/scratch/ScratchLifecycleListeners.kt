package com.pronskiy.agenstorm.scratch

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.pronskiy.agenstorm.core.AgenstormPlugin

/** Takes the `NewScratchFile` slot as the IDE starts (declared under `applicationListeners`). */
class ScratchLifecycleListener : AppLifecycleListener {

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        ScratchActionInstaller.sync()
    }
}

/** Gives the slot back before Agenstorm is unloaded, so the IDE keeps its own New Scratch File action. */
class ScratchUnloadListener : DynamicPluginListener {

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (AgenstormPlugin.isOurs(pluginDescriptor)) ScratchActionInstaller.uninstall()
    }
}
