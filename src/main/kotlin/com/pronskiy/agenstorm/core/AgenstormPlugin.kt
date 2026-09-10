package com.pronskiy.agenstorm.core

import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.extensions.PluginId

/** The plugin's own identity, for the listeners that have to tell "we are being unloaded" from "someone is". */
object AgenstormPlugin {

    val ID: PluginId = PluginId.getId("com.pronskiy.agenstorm")

    fun isOurs(descriptor: PluginDescriptor): Boolean = descriptor.pluginId == ID
}
