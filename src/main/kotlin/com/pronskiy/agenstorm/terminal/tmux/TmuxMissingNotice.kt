package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormConfigurable
import com.pronskiy.agenstorm.core.AgenstormNotifications
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Step U1.5. The setting is on and a terminal opens, but there is no tmux: the tab opens as a plain shell — nothing
 * breaks — and this says so once per IDE run, rather than once ever, since a missing tmux is a fixable state.
 */
object TmuxMissingNotice {

    private val shown = AtomicBoolean()

    fun showOnce(project: Project) {
        if (!shown.compareAndSet(false, true)) return
        AgenstormNotifications.group()
            .createNotification(
                AgenstormBundle.message("terminal.tmux.missing.title"),
                AgenstormBundle.message("terminal.tmux.missing.content"),
                NotificationType.WARNING,
            )
            .addAction(
                NotificationAction.createSimpleExpiring(AgenstormBundle.message("terminal.tmux.missing.settings")) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, AgenstormConfigurable::class.java)
                },
            )
            .notify(project)
    }
}
