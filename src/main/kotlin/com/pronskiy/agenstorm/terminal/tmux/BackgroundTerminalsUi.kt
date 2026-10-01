package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import com.intellij.util.text.DateFormatUtil
import com.pronskiy.agenstorm.core.AgenstormBundle
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent

/**
 * Step U3.3: the one list both places open — a row per background terminal (what runs, the worktree, since when) with
 * *Open* and *Stop*, and *Stop All* at the bottom (Superset's "N background terminal sessions" was the model).
 */
object BackgroundTerminalsPopup {

    fun create(project: Project): JBPopup? {
        val terminals = BackgroundTerminals.getInstance()
        val sessions = terminals.sessions.value.ifEmpty { return null }
        val group = DefaultActionGroup()
        for (session in sessions) {
            val row = DefaultActionGroup(describe(session), true)
            row.add(DumbAwareAction.create(AgenstormBundle.message("terminal.tmux.background.open")) { terminals.open(project, session) })
            row.add(DumbAwareAction.create(AgenstormBundle.message("terminal.tmux.background.stop")) { terminals.stop(listOf(session)) })
            group.add(row)
        }
        group.addSeparator()
        val stopAll = AgenstormBundle.message(if (sessions.size == 1) "terminal.tmux.background.stop" else "terminal.tmux.background.stop.all")
        group.add(DumbAwareAction.create(stopAll) { terminals.stop(sessions) })
        return JBPopupFactory.getInstance().createActionGroupPopup(
            AgenstormBundle.message("terminal.tmux.background.title"), group, SimpleDataContext.getProjectContext(project),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
        )
    }

    /** `✳ Claude Code — fix-login · since Today 13:05`. */
    fun describe(session: TmuxSession): String {
        val since = DateFormatUtil.formatPrettyDateTime(session.createdEpochSeconds * 1000)
        return TmuxBackgroundPlan.worktreeName(session)?.let { AgenstormBundle.message("terminal.tmux.background.row", session.label, it, since) }
            ?: AgenstormBundle.message("terminal.tmux.background.row.nowhere", session.label, since)
    }

    fun count(size: Int): String = AgenstormBundle.message("terminal.tmux.background.count", size)
}

/** Step U3.3: *N background terminals* in the Terminal tool window's title bar, next to the maximize toggle; hidden while there are none. */
class BackgroundTerminalsAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val count = BackgroundTerminals.getInstance().sessions.value.size
        e.presentation.isEnabledAndVisible = e.project != null && count > 0
        if (count == 0) return
        e.presentation.text = BackgroundTerminalsPopup.count(count)
        e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val popup = BackgroundTerminalsPopup.create(e.project ?: return) ?: return
        val component = e.inputEvent?.component
        if (component != null) popup.showUnderneathOf(component) else popup.showInBestPositionFor(e.dataContext)
    }
}

/** Step U3.3: *N background terminals* in the status bar, of every window, while there are any. Registered in `agenstorm-terminal.xml`. */
class BackgroundTerminalsWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = ID

    override fun getDisplayName(): String = AgenstormBundle.message("terminal.tmux.background.widget")

    override fun isAvailable(project: Project): Boolean = BackgroundTerminals.getInstance().sessions.value.isNotEmpty()

    override fun createWidget(project: Project): StatusBarWidget = BackgroundTerminalsWidget(project)

    override fun isEnabledByDefault(): Boolean = true

    companion object {
        const val ID = "agenstorm.backgroundTerminals"
    }
}

class BackgroundTerminalsWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    override fun ID(): String = BackgroundTerminalsWidgetFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = BackgroundTerminals.getInstance().sessions.value.size.let { if (it == 0) "" else BackgroundTerminalsPopup.count(it) }

    override fun getTooltipText(): String = AgenstormBundle.message("terminal.tmux.background.tooltip")

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        val popup = BackgroundTerminalsPopup.create(project) ?: return@Consumer
        popup.show(RelativePoint(event.component, Point(0, -popup.content.preferredSize.height)))
    }
}
