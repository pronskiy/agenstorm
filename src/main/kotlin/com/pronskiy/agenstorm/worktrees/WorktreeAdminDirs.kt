package com.pronskiy.agenstorm.worktrees

import com.intellij.openapi.util.io.FileUtil
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name

/**
 * Step T1.2. What `git worktree add` leaves per linked worktree under `<common git dir>/worktrees/<id>/`: `gitdir`
 * names the worktree's `.git` file (absolute, or relative to the admin dir with `worktree.useRelativePaths`), and
 * `locked` exists while the worktree is locked, holding the reason. git4idea's `GitWorkingTree` says only whether a
 * worktree is locked, so the reason and the creation time come from here.
 */
object WorktreeAdminDirs {

    data class Admin(val id: String, val lockReason: String?, val createdAt: Long)

    /**
     * What the registry's poll compares (T4.8): each admin dir's name, whether it holds `locked`, and when its `HEAD`
     * last changed — enough to notice a worktree coming or going, a lock, or a branch switched inside one, with one
     * directory listing and a few `stat`s. Empty when there is no `worktrees/` folder.
     */
    fun signature(worktreesDir: Path): String {
        if (!Files.isDirectory(worktreesDir)) return ""
        val dirs = try {
            Files.list(worktreesDir).use { stream -> stream.filter(Files::isDirectory).toList() }
        } catch (_: IOException) {
            return ""
        }
        return dirs.map { dir ->
            val head = try {
                Files.getLastModifiedTime(dir.resolve("HEAD")).toMillis()
            } catch (_: IOException) {
                0L
            }
            "${dir.name}:${Files.exists(dir.resolve("locked"))}:$head"
        }.sorted().joinToString("|")
    }

    /** The admin data keyed by the worktree's system-independent path; an unreadable or half-written admin dir is skipped. */
    fun read(worktreesDir: Path): Map<String, Admin> {
        if (!Files.isDirectory(worktreesDir)) return emptyMap()
        val dirs = try {
            Files.list(worktreesDir).use { stream -> stream.filter(Files::isDirectory).toList() }
        } catch (_: IOException) {
            return emptyMap()
        }
        return dirs.mapNotNull(::admin).toMap()
    }

    private fun admin(dir: Path): Pair<String, Admin>? = try {
        val gitdir = dir.resolve("gitdir")
        if (!Files.isRegularFile(gitdir)) {
            null
        } else {
            val dotGit = dir.resolve(Files.readString(gitdir).trim()).normalize()
            val worktree = FileUtil.toSystemIndependentName(dotGit.parent.toString())
            val locked = dir.resolve("locked")
            val reason = if (Files.isRegularFile(locked)) Files.readString(locked).trim().ifEmpty { null } else null
            val createdAt = Files.readAttributes(dir, BasicFileAttributes::class.java).creationTime().toMillis()
            worktree to Admin(dir.name, reason, createdAt)
        }
    } catch (_: IOException) {
        null
    }
}
