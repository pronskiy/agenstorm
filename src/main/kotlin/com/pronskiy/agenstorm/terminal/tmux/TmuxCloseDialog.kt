package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.CommonBundle
import com.intellij.execution.ExecutionBundle
import com.intellij.ide.GeneralSettings
import com.intellij.ide.ProcessCloseConfirmation
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.Messages

/**
 * Step U1.6 (decision 92). The platform's *Process … Is Running* dialog, for tmux tabs the platform cannot see running.
 * `TerminateRemoteProcessDialog.show` returns a different type in 262 and 263, so it is rebuilt from its parts, all
 * public in both: the `ExecutionBundle` texts — on purpose not ours, so it reads exactly as for a plain tab — the same
 * buttons, and the same remembered choice (`GeneralSettings.processCloseConfirmation`, the *Don't ask again* every
 * process-close dialog shares). *Disconnect* — leave it running — is offered only where keeping a session means
 * something (project close, U3.2); on ✕ it is *Terminate* or *Cancel*, as for a plain tab.
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

    fun ask(project: Project, names: List<String>, canKeep: Boolean): Answer {
        val settings = GeneralSettings.getInstance()
        remembered(settings.processCloseConfirmation, canKeep)?.let { return it }
        val options = buildList {
            add(ExecutionBundle.message("button.terminate"))
            if (canKeep) add(ExecutionBundle.message("button.disconnect"))
            add(CommonBundle.getCancelButtonText())
        }
        val doNotAsk = object : DoNotAskOption.Adapter() {
            override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                if (!isSelected) return
                when (fromButton(exitCode, canKeep)) {
                    Answer.TERMINATE -> settings.processCloseConfirmation = ProcessCloseConfirmation.TERMINATE
                    Answer.KEEP -> settings.processCloseConfirmation = ProcessCloseConfirmation.DISCONNECT
                    Answer.CANCEL -> Unit
                }
            }
        }
        val title = names.singleOrNull()?.let { ExecutionBundle.message("process.is.running.dialog.title", it) }
            ?: ExecutionBundle.message("processes.are.running.dialog.title")
        val message = names.singleOrNull()?.let { ExecutionBundle.message("terminate.process.confirmation.text", it) }
            ?: ExecutionBundle.message("terminate.processes.confirmation.text", names.joinToString(", ") { "'$it'" })
        val button = Messages.showDialog(project, message, title, options.toTypedArray(), 0, Messages.getWarningIcon(), doNotAsk)
        return fromButton(button, canKeep)
    }
}
