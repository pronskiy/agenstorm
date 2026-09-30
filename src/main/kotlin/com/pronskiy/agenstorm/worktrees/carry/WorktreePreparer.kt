package com.pronskiy.agenstorm.worktrees.carry

import com.intellij.openapi.project.Project
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.worktrees.WorktreeExcludes
import java.nio.file.Path

/**
 * Steps T2.2–T2.5. Readies a worktree "+" just made, before the window switches to it: the main checkout's `.idea`
 * (T2.4), the heavy folders (T2.3), then the files `.worktreeinclude` names (T2.2) — folders first, so a pattern that
 * reaches into `vendor/` cannot leave a stub that looks like the folder is there. Blocking: call it off the EDT.
 */
object WorktreePreparer {

    fun prepare(project: Project, main: Path, worktree: Path) {
        IdeaSeeder.seed(main.resolve(".idea"), worktree.resolve(".idea"))
        val worktreeFolders = listOf(WorktreeExcludes.configuredFolder(), ".claude/worktrees")
        HeavyFolders.clone(main, worktree, HeavyFolders.parse(AgenstormSettings.getInstance().state.worktreesCloneFolders), skip = worktreeFolders)
        CarryOver(CarryOver.gitLsFiles(project, main)).run(main, worktree, skip = worktreeFolders)
    }
}
