package com.pronskiy.agenstorm.tabs.offload

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormConfigurable
import com.pronskiy.agenstorm.core.AgenstormNotifications
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step P2.5. Says once, the first time a project is offloaded, what just happened and how to get the project
 * back — closing a project on its own is not something to do silently for a user who never asked (decision 65).
 * `projectsOffloadNoticeShown` is written before the balloon is shown, so two offloads in one sweep give one balloon.
 */
object OffloadNotice {

    @Synchronized
    fun showOnce(name: String, reason: Offloader.Reason) {
        val settings = AgenstormSettings.getInstance()
        if (settings.state.projectsOffloadNoticeShown) return
        settings.state.projectsOffloadNoticeShown = true
        val content = when (reason) {
            Offloader.Reason.IDLE -> AgenstormBundle.message("tabs.offload.notice.idle", name, duration(settings.state.projectsOffloadAfterMinutes))
            Offloader.Reason.CAP -> AgenstormBundle.message("tabs.offload.notice.cap", name, settings.state.projectsMaxLoaded)
        }
        AgenstormNotifications.group()
            .createNotification(AgenstormBundle.message("tabs.offload.notice.title"), content, NotificationType.INFORMATION)
            .addAction(
                NotificationAction.createSimple(AgenstormBundle.message("tabs.offload.notice.settings")) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(null, AgenstormConfigurable::class.java)
                },
            )
            .notify(null)
    }

    /** "2 hours" for 120, "90 minutes" for 90. */
    fun duration(minutes: Int): String =
        if (minutes >= 60 && minutes % 60 == 0) AgenstormBundle.message("tabs.offload.duration.hours", minutes / 60)
        else AgenstormBundle.message("tabs.offload.duration.minutes", minutes)
}
