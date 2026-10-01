package com.pronskiy.agenstorm.worktrees

import com.intellij.openapi.project.Project
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.GitCommonDir
import com.pronskiy.agenstorm.core.ProjectNameProvider
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Names a linked worktree's project `<main checkout>:<worktree>` — `app:fix-login` rather than the bare folder name —
 * in the project tabs and the window title, while the worktree feature is on; the main checkout keeps its own name.
 * Roman, 2026-09-30: the project tab "is currently changed to worktree name … it should either stay the same, or
 * change to 'project:worktree'" — the latter, since a busy worktree left behind by the switch would otherwise show a
 * second tab of the same name. Read from the disk once per path ([LinkedWorktree]), since it is asked while painting.
 */
class WorktreeProjectNames : ProjectNameProvider {

    private val mains = ConcurrentHashMap<String, Result>()

    private data class Result(val main: Path?)

    override fun displayName(project: Project): String? {
        if (!AgenstormSettings.getInstance().state.worktreesEnabled) return null
        val base = project.basePath ?: return null
        val main = mains.computeIfAbsent(base) { Result(LinkedWorktree.mainCheckout(Path.of(it))) }.main ?: return null
        return label(LinkedWorktree.projectName(main), Path.of(base).fileName.toString())
    }

    companion object {
        fun label(mainName: String, worktree: String): String = "$mainName:$worktree"
    }
}

/**
 * Where a linked worktree's main checkout is: the parent of its common git dir ([GitCommonDir]). A submodule and a
 * main checkout are not linked worktrees, and a bare repository has no main checkout.
 */
object LinkedWorktree {

    fun mainCheckout(base: Path): Path? =
        GitCommonDir.ofRoot(base)?.takeIf { it.isLinkedWorktree }?.commonDir?.takeIf { it.fileName?.toString() == ".git" }?.parent

    /** What the main checkout's project is called: `.idea/.name` when the project was renamed, else its folder's name. */
    fun projectName(main: Path): String = try {
        main.resolve(".idea/.name").takeIf(Files::isRegularFile)?.let { Files.readString(it).trim() }?.ifEmpty { null }
    } catch (_: IOException) {
        null
    } ?: main.fileName.toString()
}
