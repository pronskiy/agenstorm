package com.pronskiy.agenstorm.worktrees.carry

import com.pronskiy.agenstorm.worktrees.WorktreeExcludes
import java.nio.file.Files
import java.nio.file.Path

/**
 * Step T2.3. The folders a package manager fills — `vendor`, `node_modules` by default — are cloned whole from the main
 * checkout into a new worktree, copy-on-write, so it resolves without a `composer install` and costs next to no disk
 * (T0.1: 206 MB of `vendor/` in 3 s, about 6 MB). A folder the worktree has in part already — something tracked in
 * it, say — is filled in rather than skipped ([TreeCloner.cloneMissing]); a file already there is left alone.
 */
object HeavyFolders {

    const val DEFAULT = "vendor, node_modules"

    /** The setting as relative folders: comma or newline separated, each one a safe relative path, duplicates dropped. */
    fun parse(setting: String?): List<String> =
        setting.orEmpty().split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull(WorktreeExcludes::relativeOrNull)
            .distinct()

    /** Returns the folders that were cloned. */
    fun clone(main: Path, worktree: Path, folders: List<String>, skip: List<String> = emptyList(), cloner: TreeCloner = TreeCloner()): List<String> =
        folders
            .filter { WorktreeExcludes.relativeOrNull(it) == it }
            .filterNot { folder -> skip.any { folder == it || folder.startsWith("$it/") || it.startsWith("$folder/") } }
            .filter { Files.isDirectory(main.resolve(it)) && cloner.cloneMissing(main.resolve(it), worktree.resolve(it)) }
}
