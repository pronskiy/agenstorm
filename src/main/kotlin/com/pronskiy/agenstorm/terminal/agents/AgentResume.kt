package com.pronskiy.agenstorm.terminal.agents

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.pronskiy.agenstorm.terminal.tmux.TmuxTabs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Step X3.3. Resumes a past session in a new Terminal tab of its project (decision 103): the tab opens in the session's
 * folder — wrapped in tmux when Epic U is on, like any tab — and `claude --resume <id>` is typed into its shell once the
 * shell is ready, so the user's PATH and shell setup apply. A plain tab is ready when its shell integration is; inside
 * tmux that never comes (X0.3), so there the shell is ready once its prompt is on the screen and holds still.
 */
object AgentResume {

    private val LOG = logger<AgentResume>()
    private val SESSION_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** The line typed into the shell; null for an id that is not one, so nothing else is ever typed. */
    fun command(sessionId: String): String? = if (SESSION_ID.matches(sessionId)) "claude --resume $sessionId" else null

    /** EDT. */
    fun start(project: Project, cwd: String, sessionId: String, title: String) {
        val command = command(sessionId) ?: return
        val tab = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
            .workingDirectory(cwd)
            .tabName(title)
            .requestFocus(true)
            .createTab()
        // The shell starts only once the tool window shows.
        TmuxTabs.showToolWindow(project, focus = true)
        val view = tab.view
        view.coroutineScope.launch {
            val how = ready(view)
            LOG.info("Agenstorm: resuming Claude session $sessionId in ${project.name} ($how)")
            withContext(Dispatchers.EDT) { view.sendText("$command\n") }
        }
    }

    private suspend fun ready(view: TerminalView): String {
        if (withTimeoutOrNull(INTEGRATION_WAIT) { view.shellIntegrationDeferred.await() } != null) return "shell integration"
        val settled = withTimeoutOrNull(PROMPT_WAIT) {
            var last = -1L
            var stillFor = 0
            while (stillFor < STILL_TICKS) {
                delay(TICK)
                val (length, stamp) = withContext(Dispatchers.EDT) { view.outputModels.active.value.let { it.textLength to it.modificationStamp } }
                stillFor = if (length > 0 && stamp == last) stillFor + 1 else 0
                last = stamp
            }
        }
        return if (settled != null) "prompt" else "timed out"
    }

    private val INTEGRATION_WAIT = 3.seconds
    private val PROMPT_WAIT = 15.seconds
    private val TICK = 100.milliseconds
    private const val STILL_TICKS = 4
}
