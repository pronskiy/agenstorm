package com.pronskiy.agenstorm.core

import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager

/** The one balloon group the plugin registers in `plugin.xml`; every feature notifies through it. */
object AgenstormNotifications {

    const val GROUP_ID = "Agenstorm"

    fun group(): NotificationGroup = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
}
