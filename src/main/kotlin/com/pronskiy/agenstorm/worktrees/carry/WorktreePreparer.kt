package com.pronskiy.agenstorm.worktrees.carry

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.worktrees.WorktreeExcludes
import java.nio.file.Files
import java.nio.file.Path

/**
 * Steps T2.2–T2.5. Readies a worktree "+" just made, before the window switches to it: the main checkout's `.idea`
 * (T2.4), the heavy folders (T2.3), then the files `.worktreeinclude` names (T2.2) — folders first, so a pattern that
 * reaches into `vendor/` cannot leave a stub that looks like the folder is there. Blocking: call it off the EDT.
 *
 * T4.8: [prepareMadeElsewhere] does the same for a worktree a terminal or Claude Code made — on its first open here, or
 * as soon as it appears — more carefully, since it may have been worked in already: `.idea` only when it has none, a
 * heavy folder only when it has none at all, and, as always, nothing that is there overwritten.
 */
object WorktreePreparer {

    private val LOG = logger<WorktreePreparer>()

    fun prepare(project: Project, main: Path, worktree: Path) {
        IdeaSeeder.seed(main.resolve(".idea"), worktree.resolve(".idea"))
        val worktreeFolders = listOf(WorktreeExcludes.configuredFolder(), ".claude/worktrees")
        HeavyFolders.clone(main, worktree, HeavyFolders.parse(AgenstormSettings.getInstance().state.worktreesCloneFolders), skip = worktreeFolders)
        CarryOver(CarryOver.gitLsFiles(project, main)).run(main, worktree, skip = worktreeFolders)
    }

    /** [ideaSources]: where to take `.idea` from, first one that has it — the main checkout, then the worktree the window shows. */
    fun prepareMadeElsewhere(project: Project, main: Path, worktree: Path, ideaSources: List<Path> = listOf(main)) {
        if (!Files.isDirectory(worktree)) return
        seedIdea(worktree, ideaSources)
        val worktreeFolders = listOf(WorktreeExcludes.configuredFolder(), ".claude/worktrees")
        val cloned = HeavyFolders.clone(main, worktree, HeavyFolders.parse(AgenstormSettings.getInstance().state.worktreesCloneFolders), skip = worktreeFolders, onlyAbsent = true)
        val carried = CarryOver(CarryOver.gitLsFiles(project, main)).run(main, worktree, skip = worktreeFolders)
        LOG.info("Prepared $worktree, made elsewhere: cloned ${cloned.joinToString().ifEmpty { "nothing" }}, carried ${carried.joinToString().ifEmpty { "nothing" }}")
    }

    /** T2.4, lazily: the `.idea` of the first of [sources] that has one, when [worktree] has none. */
    fun seedIdea(worktree: Path, sources: List<Path>) {
        val idea = worktree.resolve(".idea")
        if (Files.exists(idea) || !Files.isDirectory(worktree)) return
        val source = sources.filter { it != worktree }.map { it.resolve(".idea") }.firstOrNull(Files::isDirectory) ?: return
        IdeaSeeder.seed(source, idea)
    }
}
