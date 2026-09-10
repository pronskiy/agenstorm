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
 * Step G1.3, widened by step K1.1. Keeps the generated shims on disk, in a directory of its own under the
 * IDE's system path that [TerminalOpenExecOptionsCustomizer] prepends to a new terminal's PATH.
 *
 * Two scripts live there: one copy of `resources/terminal/open.sh` per configured command name, and the
 * `$EDITOR` shim `resources/terminal/edit.sh` under [EDIT_SHIM_NAME]. Both are mode 0755. The directory
 * carries a stamp of the scripts' content and the names they were written for, so an update that changes a
 * script rewrites every copy, and a name the user removed from the setting — or a feature they switched
 * off — stops shadowing anything. Content, not the plugin version, is what the stamp hashes: the platform
 * offers no public way to read a plugin's version in 262, and the content is what actually has to be fresh.
 */
@Service(Service.Level.APP)
class OpenShimScriptHolder {

    /** The PATH entry: it holds nothing but the generated shims and their stamp. */
    val binDir: Path = PathManager.getSystemDir().resolve("agenstorm").resolve("bin")

    private val templates = HashMap<String, String?>()

    /**
     * Writes a shim for every usable name in [commandNames], plus the `$EDITOR` shim when [editShim] is on,
     * and removes the shims of names — or of features — that are no longer configured. Returns [binDir], or
     * null when nothing could be installed. Does file I/O — call it off the EDT. Safe to call as often as a
     * terminal is opened: it rewrites only when something changed.
     */
    @Synchronized
    fun install(commandNames: List<String>, editShim: Boolean): Path? {
        val scripts = LinkedHashMap<String, String>()
        val names = commandNames.filter(::isUsableName).distinct().sorted()
        if (names.isNotEmpty()) {
            val open = template(OPEN_TEMPLATE) ?: return null
            names.forEach { scripts[it] = open }
        }
        // Written last, so a user who happens to list the edit shim's own name among the `open` names gets
        // the edit shim rather than an `open` copy that nothing would ever reach through $EDITOR anyway.
        if (editShim) scripts[EDIT_SHIM_NAME] = template(EDIT_TEMPLATE) ?: return null
        if (scripts.isEmpty()) return null
        val stamp = stamp(scripts)
        return try {
            NioFiles.createDirectories(binDir)
            if (stamp != currentStamp() || scripts.keys.any { !Files.isRegularFile(binDir.resolve(it)) }) {
                Files.deleteIfExists(binDir.resolve(STAMP_FILE))
                removeShimsOtherThan(scripts.keys)
                scripts.forEach { (name, script) -> write(binDir.resolve(name), script) }
                Files.writeString(binDir.resolve(STAMP_FILE), stamp)
                LOG.info("Agenstorm: installed the terminal shims in $binDir as ${scripts.keys.joinToString()}")
            }
            binDir
        } catch (e: IOException) {
            LOG.warn("Agenstorm: could not install the terminal shims in $binDir", e)
            null
        }
    }

    private fun template(resource: String): String? = templates.getOrPut(resource) {
        val stream = OpenShimScriptHolder::class.java.getResourceAsStream(resource)
        if (stream == null) {
            LOG.error("Agenstorm: the terminal shim template $resource is missing from the plugin")
            null
        } else {
            stream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
        }
    }

    private fun currentStamp(): String? = try {
        Files.readString(binDir.resolve(STAMP_FILE))
    } catch (_: IOException) {
        null
    }

    private fun removeShimsOtherThan(names: Collection<String>) {
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

        /**
         * The name the `$EDITOR` shim is written under. Not a setting: `$EDITOR` carries this file's
         * absolute path, so the name is never typed and never has to dodge a command the user already has.
         */
        const val EDIT_SHIM_NAME: String = "agenstorm-edit"

        /** What the edit shim falls back to when the IDE declines or is gone; K1.2 fills it in. */
        const val EDITOR_FALLBACK_ENV: String = "AGENSTORM_EDITOR_FALLBACK"

        private const val OPEN_TEMPLATE = "/terminal/open.sh"
        private const val EDIT_TEMPLATE = "/terminal/edit.sh"
        private const val STAMP_FILE = ".stamp"
        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")
        private val EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x")
        private val LOG = logger<OpenShimScriptHolder>()

        private fun isUsableName(name: String) = NAME.matches(name)

        private fun stamp(scripts: Map<String, String>): String {
            val content = scripts.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\n${it.value}" }
            val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(StandardCharsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
