package com.pronskiy.agenstorm.terminal

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormConfigurable
import com.pronskiy.agenstorm.core.AgenstormNotifications
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step J1.6. ⌘⌥M is Extract Method in both macOS keymaps — and `sql.ExtractFunctionAction` in a database
 * IDE — so on that keystroke the platform hands the editor's own action the event and Maximize Terminal
 * never runs (decision 46). This promoter says which of them wins.
 *
 * The alternative was to take the shortcut away from Extract Method, and that is worse in every direction:
 * the macOS keymaps are read-only, so `removeShortcut` means `deriveKeymap` — the user's active keymap
 * silently becomes a copy of itself — and the change would outlive the plugin. Ordering the candidates
 * changes nothing that is stored: switch the feature off, or rebind either action, and ⌘⌥M is Extract
 * Method again.
 *
 * Shaped after the platform's own `WindowActionPromoter`: the whole list comes back, sorted, rather than
 * just the winner, so nothing is dropped from the dispatch.
 */
class TerminalMaximizeShortcutPromoter : ActionPromoter {

    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction>? {
        if (!AgenstormSettings.getInstance().state.terminalMaximizeEnabled) return null
        // Nothing to promote unless this action is in the list *and* something else wanted the keystroke too.
        if (actions.size < 2 || actions.none { it is TerminalMaximizeToggleAction }) return null
        announceOnce(actions, context)
        return actions.sortedBy { if (it is TerminalMaximizeToggleAction) 0 else 1 }
    }

    /**
     * Says once that this keystroke changed hands, naming what it was, because "my Extract Method stopped
     * working" is otherwise a mystery with no trail leading back to this plugin.
     */
    private fun announceOnce(actions: List<AnAction>, context: DataContext) {
        val settings = AgenstormSettings.getInstance()
        if (settings.state.terminalMaximizeShortcutNoticeShown) return
        val project = CommonDataKeys.PROJECT.getData(context) ?: return
        val displaced = actions.filterNot { it is TerminalMaximizeToggleAction }
            .mapNotNull { it.templatePresentation.text }
            .distinct()
            .ifEmpty { return }
        settings.state.terminalMaximizeShortcutNoticeShown = true
        notify(project, displaced)
    }

    private fun notify(project: Project, displaced: List<String>) {
        val shortcut = KeymapUtil.getFirstKeyboardShortcutText(TerminalMaximizeToggleAction.ACTION_ID)
        AgenstormNotifications.group()
            .createNotification(
                AgenstormBundle.message("terminal.maximize.shortcut.notice.title", shortcut),
                AgenstormBundle.message("terminal.maximize.shortcut.notice.content", displaced.joinToString(", ")),
                NotificationType.INFORMATION,
            )
            .addAction(
                NotificationAction.createSimple(AgenstormBundle.message("terminal.maximize.shortcut.notice.settings")) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, AgenstormConfigurable::class.java)
                },
            )
            .notify(project)
    }
}
