package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.terminal.TerminalTitle
import com.intellij.terminal.TerminalTitleListener
import com.intellij.terminal.frontend.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.terminal.TerminalOptionsProvider
import kotlin.time.Duration.Companion.seconds

/**
 * Step U1.7 (decision 92). A tmux tab shows a program's own title — Claude Code's animated `✳ Claude Code` — as a plain
 * tab does. tmux forwards the title (`set-titles`), but the IDE puts a program's title in the tab only while it thinks a
 * command runs, which it learns from shell integration that tmux silences; so while a title is set it is mirrored into
 * the tab's default title. tmux never clears a finished program's title either, so while one shows, tmux is asked
 * every second whether the pane is back at its shell, and then the tab gets its own name back. A rename still wins
 * (`userDefinedTitle` comes first), and with application titles switched off in the terminal settings nothing is mirrored.
 */
class TmuxTitleMirror(private val view: TerminalView, private val session: String, private val parent: Disposable) {

    private val title: TerminalTitle = view.title
    private val original: String? = title.defaultTitle
    private var watch: Job? = null

    fun start() {
        title.addTitleListener(object : TerminalTitleListener {
            override fun onTitleChanged(terminalTitle: TerminalTitle) = mirror()
        }, parent)
        mirror()
    }

    @Synchronized
    private fun mirror() {
        val application = title.applicationTitle
        val next = defaultTitle(application, TerminalOptionsProvider.instance.showApplicationTitle, original)
        if (next != title.defaultTitle) title.change { defaultTitle = next }
        if (!application.isNullOrBlank() && watch?.isActive != true) watch = view.coroutineScope.launch { watchUntilBackAtShell() }
    }

    private suspend fun watchUntilBackAtShell() {
        while (true) {
            delay(POLL)
            val state = withContext(Dispatchers.IO) { TmuxSessions.read().firstOrNull { it.name == session } }
            if (state == null || !state.running) break
        }
        withContext(Dispatchers.EDT) {
            title.change {
                applicationTitle = null
                defaultTitle = original
            }
        }
    }

    companion object {
        private val POLL = 1.seconds

        /** The program's own title while it has one and titles are shown, else the tab's own name. */
        fun defaultTitle(applicationTitle: String?, showApplicationTitles: Boolean, original: String?): String? =
            applicationTitle?.takeIf { showApplicationTitles && it.isNotBlank() } ?: original
    }
}
