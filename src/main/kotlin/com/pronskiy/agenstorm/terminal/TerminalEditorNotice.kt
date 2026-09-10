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
 * Step K1.5. Says once, the first time a terminal gets the bridge, that `$EDITOR` now opens here — and, when
 * the user has an editor of their own, that it is still what handles everything the IDE declines.
 *
 * Taking over `$EDITOR` deserves the sentence: it is the variable a shell profile most often sets by hand,
 * and the bridge deliberately wins over one (`_INTELLIJ_FORCE_SET_*`), which is not a change to make in
 * silence. `terminalEditorNoticeShown` is written before the balloon is shown, so two terminals opening at
 * the same moment cannot produce two.
 */
object TerminalEditorNotice {

    @Synchronized
    fun showOnce(project: Project, fallback: String?) {
        val settings = AgenstormSettings.getInstance()
        if (settings.state.terminalEditorNoticeShown) return
        settings.state.terminalEditorNoticeShown = true
        notification(fallback).notify(project)
    }

    private fun notification(fallback: String?): Notification =
        AgenstormNotifications.group()
            .createNotification(
                AgenstormBundle.message("terminal.editor.notice.title"),
                when (fallback) {
                    null -> AgenstormBundle.message("terminal.editor.notice.content")
                    else -> AgenstormBundle.message("terminal.editor.notice.fallback", fallback)
                },
                NotificationType.INFORMATION,
            )
            .addAction(
                NotificationAction.createSimple(AgenstormBundle.message("terminal.editor.notice.settings")) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(null, AgenstormConfigurable::class.java)
                },
            )
}
