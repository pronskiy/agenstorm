package com.pronskiy.agenstorm.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Step U1.1. Which repository a folder belongs to, read from the disk without running git: its *common* git dir, the
 * one a main checkout and all of its linked worktrees share. A main checkout's `.git` is that folder; a linked
 * worktree's `.git` is a file naming its own git dir (`gitdir: <common git dir>/worktrees/<id>`), whose `commondir`
 * leads back to the shared one. A submodule's `.git` file names a git dir without `commondir` — a repository of its
 * own. Paths come back resolved through symlinks, so `/tmp/app` and `/private/tmp/app` are one repository. Lives in
 * `core/` because both `worktrees/` and `terminal/` ask it.
 */
object GitCommonDir {

    /** A checkout's own git dir and the common one; they differ only in a linked worktree. */
    data class Dirs(val gitDir: Path, val commonDir: Path) {
        val isLinkedWorktree: Boolean get() = gitDir != commonDir
    }

    /** The git dirs of the checkout whose root is [root], or null when [root] holds no `.git`. */
    fun ofRoot(root: Path): Dirs? = try {
        val dotGit = root.resolve(".git")
        val gitDir = when {
            Files.isDirectory(dotGit) -> dotGit
            Files.isRegularFile(dotGit) -> Files.readAllLines(dotGit)
                .firstOrNull { it.startsWith("gitdir:") }?.removePrefix("gitdir:")?.trim()?.ifEmpty { null }
                ?.let { root.resolve(it) }
            else -> null
        }?.takeIf(Files::isDirectory)?.let(::real)
        gitDir?.let { Dirs(it, commonOf(it)) }
    } catch (_: IOException) {
        null
    }

    /** The common git dir of the checkout [folder] is in — the nearest folder at or above it with a `.git` — or null. */
    fun of(folder: Path): Path? {
        var dir: Path? = folder.toAbsolutePath().normalize()
        while (dir != null) {
            ofRoot(dir)?.let { return it.commonDir }
            dir = dir.parent
        }
        return null
    }

    private fun commonOf(gitDir: Path): Path =
        gitDir.resolve("commondir").takeIf(Files::isRegularFile)
            ?.let { gitDir.resolve(Files.readString(it).trim()) }
            ?.takeIf(Files::isDirectory)?.let(::real)
            ?: gitDir

    private fun real(path: Path): Path = try {
        path.toRealPath()
    } catch (_: IOException) {
        path.normalize()
    }
}
