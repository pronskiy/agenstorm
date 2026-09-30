package com.pronskiy.agenstorm.worktrees.carry

import com.intellij.openapi.project.Project
import com.pronskiy.agenstorm.worktrees.WorktreeExcludes
import java.nio.file.Path

/**
 * Steps T2.2–T2.5. Readies a worktree "+" just made, before the window switches to it. So far: the files
 * `.worktreeinclude` names (T2.2). Blocking: call it off the EDT.
 */
object WorktreePreparer {

    fun prepare(project: Project, main: Path, worktree: Path) {
        CarryOver(CarryOver.gitLsFiles(project, main)).run(main, worktree, skip = listOf(WorktreeExcludes.configuredFolder(), ".claude/worktrees"))
    }
}
