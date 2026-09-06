package com.pronskiy.agenstorm.terminal

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormConfigurable
import com.pronskiy.agenstorm.core.AgenstormNotifications
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step G2.4. Says once, the first time the shim is installed, that `open` is shadowed inside IDE terminals
 * and nowhere else — shadowing a command everyone's fingers already know should never be a silent change.
 *
 * `terminalOpenNoticeShown` on the settings state is what makes it once: it is written before the balloon is
 * shown, so a second terminal opening at the same moment cannot produce a second balloon.
 */
object TerminalOpenNotice {

    @Synchronized
    fun showOnce(project: Project) {
        val settings = AgenstormSettings.getInstance()
        if (settings.state.terminalOpenNoticeShown) return
        settings.state.terminalOpenNoticeShown = true
        notification().notify(project)
    }

    private fun notification(): Notification =
        AgenstormNotifications.group()
            .createNotification(
                AgenstormBundle.message("terminal.open.notice.title"),
                AgenstormBundle.message("terminal.open.notice.content"),
                NotificationType.INFORMATION,
            )
            .addAction(
                NotificationAction.createSimple(AgenstormBundle.message("terminal.open.notice.settings")) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(null, AgenstormConfigurable::class.java)
                },
            )
}
