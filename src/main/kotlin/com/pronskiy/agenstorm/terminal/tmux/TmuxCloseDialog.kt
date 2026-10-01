package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.CommonBundle
import com.intellij.ide.GeneralSettings
import com.intellij.ide.ProcessCloseConfirmation
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.Messages
import com.pronskiy.agenstorm.core.AgenstormBundle

/**
 * Step U1.6 (decision 92). The platform's *Process … Is Running* dialog, for tmux tabs the platform cannot see running.
 * `TerminateRemoteProcessDialog.show` returns a different type in 262 and 263, so it is rebuilt from its parts, all
 * public in both: the same texts (in our bundle, word for word — `ExecutionBundle` itself is internal in 263), the same
 * buttons, and the same remembered choice (`GeneralSettings.processCloseConfirmation`, the *Don't ask again* every
 * process-close dialog shares). *Disconnect* — leave it running — is offered only where keeping a session means
 * something (project close, U3.2); on ✕ it is *Terminate* or *Cancel*, as for a plain tab. Quitting asks once for all
 * of them with a prompt of our own ([askQuit], decision 92), under the same remembered choice.
 */
object TmuxCloseDialog {

    enum class Answer { TERMINATE, KEEP, CANCEL }

    /** What the remembered *Don't ask again* choice answers, or null when the user wants to be asked. */
    fun remembered(setting: ProcessCloseConfirmation, canKeep: Boolean): Answer? = when (setting) {
        ProcessCloseConfirmation.ASK -> null
        ProcessCloseConfirmation.DISCONNECT -> if (canKeep) Answer.KEEP else Answer.TERMINATE
        else -> Answer.TERMINATE
    }

    /** The button pressed: *Terminate*, *Disconnect* when offered, else *Cancel* (or the dialog closed). */
    fun fromButton(index: Int, canKeep: Boolean): Answer = when {
        index == 0 -> Answer.TERMINATE
        index == 1 && canKeep -> Answer.KEEP
        else -> Answer.CANCEL
    }

    /** The quit prompt's button pressed: *Keep Running*, *Stop All*, else *Cancel* (or the dialog closed). */
    fun fromQuitButton(index: Int): Answer = when (index) {
        0 -> Answer.KEEP
        1 -> Answer.TERMINATE
        else -> Answer.CANCEL
    }

    fun ask(project: Project, names: List<String>, canKeep: Boolean): Answer {
        val settings = GeneralSettings.getInstance()
        remembered(settings.processCloseConfirmation, canKeep)?.let { return it }
        val options = buildList {
            add(AgenstormBundle.message("terminal.tmux.close.terminate"))
            if (canKeep) add(AgenstormBundle.message("terminal.tmux.close.disconnect"))
            add(CommonBundle.getCancelButtonText())
        }
        val doNotAsk = rememberingAs(settings) { fromButton(it, canKeep) }
        val title = names.singleOrNull()?.let { AgenstormBundle.message("terminal.tmux.close.title", it) }
            ?: AgenstormBundle.message("terminal.tmux.close.title.many")
        val message = names.singleOrNull()?.let { AgenstormBundle.message("terminal.tmux.close.message", it) }
            ?: AgenstormBundle.message("terminal.tmux.close.message.many", names.joinToString(", ") { "'$it'" })
        val button = Messages.showDialog(project, message, title, options.toTypedArray(), 0, Messages.getWarningIcon(), doNotAsk)
        return fromButton(button, canKeep)
    }

    /**
     * Step U3.2 (decision 92): one prompt on quit for every terminal still running, [labels] saying what runs where
     * (`claude in fix-login`). *Keep Running* keeps them as background terminals, *Stop All* ends them.
     */
    fun askQuit(labels: List<String>): Answer {
        val settings = GeneralSettings.getInstance()
        remembered(settings.processCloseConfirmation, canKeep = true)?.let { return it }
        val one = labels.size == 1
        val options = arrayOf(
            AgenstormBundle.message("terminal.tmux.quit.keep"),
            AgenstormBundle.message(if (one) "terminal.tmux.quit.stop.one" else "terminal.tmux.quit.stop"),
            CommonBundle.getCancelButtonText(),
        )
        val title = AgenstormBundle.message(if (one) "terminal.tmux.quit.title.one" else "terminal.tmux.quit.title")
        val message = if (one) AgenstormBundle.message("terminal.tmux.quit.message.one", labels.single())
        else AgenstormBundle.message("terminal.tmux.quit.message", labels.size, labels.joinToString(", "))
        val button = Messages.showDialog(null as Project?, message, title, options, 0, Messages.getWarningIcon(), rememberingAs(settings, ::fromQuitButton))
        return fromQuitButton(button)
    }

    /** *Don't ask again*, remembered where every process-close dialog keeps it. */
    private fun rememberingAs(settings: GeneralSettings, answer: (Int) -> Answer) = object : DoNotAskOption.Adapter() {
        override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
            if (!isSelected) return
            when (answer(exitCode)) {
                Answer.TERMINATE -> settings.processCloseConfirmation = ProcessCloseConfirmation.TERMINATE
                Answer.KEEP -> settings.processCloseConfirmation = ProcessCloseConfirmation.DISCONNECT
                Answer.CANCEL -> Unit
            }
        }
    }
}
