package com.pronskiy.agenstorm.worktrees.carry

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Steps T2.2–T2.3. Copies a file or a folder into a new worktree, copy-on-write where the file system allows it:
 * `cp -c -R` on macOS (APFS clonefile — T0.1 cloned a 206 MB `vendor/` in 3 s for about 6 MB of disk),
 * `cp -R --reflink=auto` on Linux (btrfs, XFS; a plain copy elsewhere), and a Java copy that keeps symbolic links as
 * links when neither is there or `cp` fails. Something already at the target is never overwritten. Blocking.
 */
class TreeCloner(
    private val command: (Path, Path) -> List<String>? = ::platformCommand,
    private val exec: (List<String>) -> Boolean = ::run,
) {

    /**
     * Like [clone], but a folder that exists at [target] already is filled in rather than skipped: each child of
     * [source] it lacks is cloned, folders it has are descended into, files it has are left alone. Returns whether
     * anything was copied.
     */
    fun cloneMissing(source: Path, target: Path): Boolean {
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) return clone(source, target)
        val children = try {
            Files.list(source).use { it.toList() }
        } catch (e: IOException) {
            LOG.warn("Could not list $source", e)
            return false
        }
        return children.map { cloneMissing(it, target.resolve(it.fileName.toString())) }.any { it }
    }

    /** True when [target] was created; false when something was there already or nothing could be copied. */
    fun clone(source: Path, target: Path): Boolean {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            Files.createDirectories(target.parent)
            val cp = command(source, target)
            if (cp != null && exec(cp)) return true
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) NioFiles.deleteRecursively(target)
            copy(source, target)
            true
        } catch (e: IOException) {
            LOG.warn("Could not copy $source to $target", e)
            false
        }
    }

    private fun copy(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val to = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(to)
                else Files.copy(path, to, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }

    companion object {
        private val LOG = logger<TreeCloner>()
        private const val TIMEOUT_MS = 10 * 60 * 1000

        fun platformCommand(source: Path, target: Path): List<String>? = when {
            SystemInfo.isMac -> listOf("/bin/cp", "-c", "-R", source.toString(), target.toString())
            SystemInfo.isLinux -> listOf("cp", "-R", "--reflink=auto", source.toString(), target.toString())
            else -> null
        }

        private fun run(command: List<String>): Boolean = try {
            val output = CapturingProcessHandler(GeneralCommandLine(command)).runProcess(TIMEOUT_MS)
            val ok = output.exitCode == 0 && !output.isTimeout
            if (!ok) LOG.info("${command.first()} failed (${output.exitCode}): ${output.stderr.trim()}; copying instead")
            ok
        } catch (e: Exception) {
            LOG.info("${command.first()} could not run; copying instead", e)
            false
        }
    }
}
