package com.pronskiy.agenstorm.notifications

import com.intellij.notification.Notification
import com.intellij.notification.Notifications

/**
 * Epic L: hands every published notification to [NotificationAutoDismissService].
 *
 * Registered twice, in `applicationListeners` **and** `projectListeners`: `Notifications.TOPIC` is declared
 * `BroadcastDirection.NONE`, and `Notifications.Bus.doNotify` publishes a project-scoped notification on that
 * project's bus and everything else on the application's. Either registration alone sees half of them.
 */
class NotificationAutoDismissListener : Notifications {

    override fun notify(notification: Notification) {
        NotificationAutoDismissService.getInstance().schedule(notification)
    }
}
