package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Step U2.4. Where the `open` and `$EDITOR` shims find the IDE once the endpoint in their environment is gone: a
 * terminal can outlive the project it started in — a tmux session handed to another window (U2.2), or one that
 * outlived the IDE itself (U3). Every running [OpenRequestServer] lists itself here, one file per project, as
 * `port<TAB>token<TAB>project folder`, and takes the file away when it stops; the shims try the project whose folder
 * holds their `$PWD` first. The folder sits beside the shims' own (`../endpoints` from the script), so they need no
 * variable to find it. The tokens make it a secret: the folder is the user's alone and each file is written whole.
 * A previous run's files — stale ports and tokens — are cleared on the first write of this one.
 */
object TerminalEndpoints {

    val dir: Path get() = PathManager.getSystemDir().resolve("agenstorm").resolve("endpoints")

    private val cleared = AtomicBoolean()
    private val LOG = logger<TerminalEndpoints>()

    fun publish(projectFolder: String, port: Int, token: String) {
        val line = line(projectFolder, port, token) ?: return
        if (SystemInfo.isWindows) return
        try {
            if (cleared.compareAndSet(false, true)) NioFiles.deleteRecursively(dir)
            Files.createDirectories(dir)
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
            val temp = Files.createTempFile(dir, ".endpoint", null)
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"))
            Files.writeString(temp, line, StandardCharsets.UTF_8)
            Files.move(temp, fileFor(projectFolder), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            LOG.warn("Agenstorm: could not list the terminal endpoint of $projectFolder in $dir", e)
        }
    }

    fun withdraw(projectFolder: String) {
        try {
            Files.deleteIfExists(fileFor(projectFolder))
        } catch (e: IOException) {
            LOG.debug("Could not remove the terminal endpoint of $projectFolder", e)
        }
    }

    /** One file per project folder, named by a hash of it. */
    fun fileFor(projectFolder: String): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(projectFolder.toByteArray(StandardCharsets.UTF_8))
        return dir.resolve(digest.take(8).joinToString("") { "%02x".format(it) })
    }

    /** What the shims read with `IFS=<TAB> read -r port token base`; null for a folder the line could not carry. */
    fun line(projectFolder: String, port: Int, token: String): String? =
        if (port <= 0 || projectFolder.any { it == '\t' || it == '\n' }) null else "$port\t$token\t$projectFolder\n"
}
