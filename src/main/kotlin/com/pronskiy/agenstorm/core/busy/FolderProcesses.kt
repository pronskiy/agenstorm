package com.pronskiy.agenstorm.core.busy

import com.intellij.openapi.extensions.ExtensionPointName
import java.nio.file.Path

/**
 * Step U4.1. Programs that outlive their project — Epic U's tmux sessions, which a worktree switch hands on and a close
 * can keep in the background — asked about a folder before Agenstorm deletes it (removing or archiving a worktree, T4)
 * or merges it (T4.3): what runs there, and to end all of it. The project's own busy guards ([ProjectBusyGuard]) cannot
 * see them, since a tmux tab is never busy (U2.3) and a background terminal has no tab at all. An extension point of
 * Agenstorm's own, `com.pronskiy.agenstorm.folderProcesses`, so the tmux one lives with the terminal's
 * optional-dependency file and `worktrees/` keeps importing only `core/`. Both calls block: call them off the EDT.
 */
interface FolderProcesses {

    /** What runs with its working directory inside [folder], one short name each (`✳ Claude Code`, `npm`). */
    fun running(folder: Path): List<String>

    /** Ends everything this extension holds inside [folder] — running, idle, or kept in the background. */
    fun end(folder: Path)

    companion object {
        val EP_NAME: ExtensionPointName<FolderProcesses> = ExtensionPointName.create("com.pronskiy.agenstorm.folderProcesses")

        fun running(folder: Path): List<String> = EP_NAME.extensionList.flatMap { it.running(folder) }

        fun end(folder: Path) = EP_NAME.extensionList.forEach { it.end(folder) }
    }
}
