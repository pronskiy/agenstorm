package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.openapi.diagnostic.logger
import com.pronskiy.agenstorm.core.busy.FolderProcesses
import java.io.IOException
import java.nio.file.Path

/**
 * Step U4.1 (decision 91). The tmux sessions of a worktree that is about to be removed, archived or merged back: those
 * tagged with it and those with a pane working inside it. What runs there is named; ending them ends running, idle and
 * background ones alike. Registered in `agenstorm-terminal.xml`.
 */
class TmuxFolderProcesses : FolderProcesses {

    override fun running(folder: Path): List<String> = sessionsIn(folder).filter { it.running }.map { it.label }

    override fun end(folder: Path) {
        val sessions = sessionsIn(folder).ifEmpty { return }
        val tmux = Tmux.getInstance()
        sessions.forEach { tmux.run(*TmuxBackgroundPlan.stop(it.name).toTypedArray()) }
        LOG.info("Agenstorm: ended tmux sessions ${sessions.joinToString { it.name }} of $folder")
        BackgroundTerminals.getInstance().refresh()
    }

    /** tmux reports panes' real paths, the tags carry the IDE's; the folder is matched in both spellings. */
    private fun sessionsIn(folder: Path): List<TmuxSession> {
        val spellings = setOfNotNull(folder.toString(), try { folder.toRealPath().toString() } catch (_: IOException) { null })
        return TmuxBackgroundPlan.inFolder(TmuxSessions.read(), spellings)
    }

    private companion object {
        val LOG = logger<TmuxFolderProcesses>()
    }
}
