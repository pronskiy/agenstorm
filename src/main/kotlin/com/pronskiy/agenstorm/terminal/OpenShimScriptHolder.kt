package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.io.NioFiles
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.streams.asSequence

/**
 * Step G1.3. Keeps the generated `open` shim on disk, in a directory of its own under the IDE's system
 * path that [TerminalOpenExecOptionsCustomizer] prepends to a new terminal's PATH.
 *
 * One copy of `resources/terminal/open.sh` per configured command name, mode 0755. The directory carries a
 * stamp of the script's content and the names it was written for, so an update that changes the script
 * rewrites every copy and a name the user removed from the setting stops shadowing the real command.
 * Content, not the plugin version, is what the stamp hashes — the platform offers no public way to read a
 * plugin's version in 262, and the content is what actually has to be fresh.
 */
@Service(Service.Level.APP)
class OpenShimScriptHolder {

    /** The PATH entry: it holds nothing but the generated shims and their stamp. */
    val binDir: Path = PathManager.getSystemDir().resolve("agenstorm").resolve("bin")

    private val template: String? by lazy {
        val stream = OpenShimScriptHolder::class.java.getResourceAsStream(TEMPLATE)
        if (stream == null) {
            LOG.error("Agenstorm: the terminal shim template $TEMPLATE is missing from the plugin")
            null
        } else {
            stream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
        }
    }

    /**
     * Writes a shim for every usable name in [commandNames] and removes the shims of names that are no
     * longer configured. Returns [binDir], or null when nothing could be installed. Does file I/O — call it
     * off the EDT. Safe to call as often as a terminal is opened: it rewrites only when something changed.
     */
    @Synchronized
    fun install(commandNames: List<String>): Path? {
        val names = commandNames.filter(::isUsableName).distinct().sorted()
        if (names.isEmpty()) return null
        val script = template ?: return null
        val stamp = stamp(script, names)
        return try {
            NioFiles.createDirectories(binDir)
            if (stamp != currentStamp() || names.any { !Files.isRegularFile(binDir.resolve(it)) }) {
                Files.deleteIfExists(binDir.resolve(STAMP_FILE))
                removeShimsOtherThan(names)
                names.forEach { write(binDir.resolve(it), script) }
                Files.writeString(binDir.resolve(STAMP_FILE), stamp)
                LOG.info("Agenstorm: installed the terminal open shim in $binDir as ${names.joinToString()}")
            }
            binDir
        } catch (e: IOException) {
            LOG.warn("Agenstorm: could not install the terminal open shim in $binDir", e)
            null
        }
    }

    private fun currentStamp(): String? = try {
        Files.readString(binDir.resolve(STAMP_FILE))
    } catch (_: IOException) {
        null
    }

    private fun removeShimsOtherThan(names: List<String>) {
        Files.list(binDir).use { entries ->
            entries.asSequence()
                .filter { Files.isRegularFile(it) && it.fileName.toString() !in names }
                .forEach { NioFiles.deleteQuietly(it) }
        }
    }

    private fun write(path: Path, script: String) {
        Files.writeString(path, script)
        try {
            Files.setPosixFilePermissions(path, EXECUTABLE)
        } catch (_: UnsupportedOperationException) {
            // Windows has no POSIX permissions; the customizer does not install the shim there anyway.
        }
    }

    companion object {
        /**
         * Splits the comma-separated `terminalOpenCommandNames` setting. Anything that cannot safely be a
         * file name in [binDir] is dropped rather than sanitized — a name is a command, not a path.
         */
        fun parseCommandNames(text: String): List<String> =
            text.split(',').map { it.trim() }.filter(::isUsableName).distinct()

        private const val TEMPLATE = "/terminal/open.sh"
        private const val STAMP_FILE = ".stamp"
        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")
        private val EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x")
        private val LOG = logger<OpenShimScriptHolder>()

        private fun isUsableName(name: String) = NAME.matches(name)

        private fun stamp(script: String, names: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((script + '\n' + names.joinToString(",")).toByteArray(StandardCharsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
