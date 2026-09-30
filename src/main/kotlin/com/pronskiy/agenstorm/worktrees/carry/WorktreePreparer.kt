package com.pronskiy.agenstorm.worktrees.carry

import com.intellij.openapi.project.Project
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.worktrees.WorktreeExcludes
import java.nio.file.Path

/**
 * Steps T2.2–T2.5. Readies a worktree "+" just made, before the window switches to it: the files `.worktreeinclude`
 * names (T2.2), then the heavy folders (T2.3). Blocking: call it off the EDT.
 */
object WorktreePreparer {

    fun prepare(project: Project, main: Path, worktree: Path) {
        val worktreeFolders = listOf(WorktreeExcludes.configuredFolder(), ".claude/worktrees")
        CarryOver(CarryOver.gitLsFiles(project, main)).run(main, worktree, skip = worktreeFolders)
        HeavyFolders.clone(main, worktree, HeavyFolders.parse(AgenstormSettings.getInstance().state.worktreesCloneFolders), skip = worktreeFolders)
    }
}
