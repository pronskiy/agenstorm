package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.pronskiy.agenstorm.links.FileLocation
import com.pronskiy.agenstorm.links.FileLocationParser
import com.pronskiy.agenstorm.links.FileLocationResolver
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Step G1.2. Decides what the IDE does with one `open` invocation from a terminal — the whole of the
 * feature's logic, so it is where the tests live.
 *
 * The IDE only ever claims arguments it is sure about: everything else becomes [Decision.Fallback] and the
 * shim execs the real `open`, which keeps its own error messages and its own handling of `-a`, `-R`, URLs
 * and missing files. Claiming is all-or-nothing across one command line — a half-claimed `open a.php nope`
 * would swallow macOS's error for the second argument.
 *
 * Resolution order per argument: a trailing `:line[:col]` is peeled off, the remainder is resolved against
 * the shell's working directory, and only if nothing is there does [FileLocationResolver] try the project
 * base, the content roots and a unique basename the way Epic A does for a written location.
 *
 * Requires no read action of its own and must not be called while holding one — it refreshes the VFS for
 * paths the IDE has not seen yet. In production it runs on the endpoint's background coroutine.
 */
class OpenCommandRouter(project: Project, private val openUnknownFileTypes: Boolean) {

    private val resolver = FileLocationResolver(project)

    /** What the IDE should do with the command; [Decision.Fallback] means "not ours". */
    sealed interface Decision {
        data class OpenFiles(val targets: List<FileTarget>) : Decision
        data class OpenProject(val path: Path) : Decision
        data class Fallback(val reason: String) : Decision
    }

    /** One file the IDE claimed, with the 1-based caret position the argument asked for, if any. */
    data class FileTarget(val file: VirtualFile, val line: Int?, val column: Int?)

    fun route(cwd: Path, argv: List<String>): Decision {
        if (argv.isEmpty()) return Decision.Fallback("no arguments")
        argv.firstOrNull { it.startsWith("-") }?.let { return Decision.Fallback("flag $it") }
        argv.firstOrNull { SCHEME.containsMatchIn(it) }?.let { return Decision.Fallback("URL $it") }

        val files = mutableListOf<FileTarget>()
        val directories = mutableListOf<Path>()
        for (argument in argv) {
            when (val claimed = claim(cwd, argument)) {
                null -> return Decision.Fallback("cannot claim $argument")
                // `add`, not `+=`: java.nio.file.Path is itself an Iterable<Path>, so `+=` is ambiguous.
                is Claimed.Directory -> directories.add(claimed.path)
                is Claimed.File -> files.add(claimed.target)
            }
        }

        return when {
            directories.isEmpty() -> Decision.OpenFiles(files)
            files.isNotEmpty() -> Decision.Fallback("mixes files and directories")
            directories.size > 1 -> Decision.Fallback("more than one directory")
            else -> Decision.OpenProject(directories.single())
        }
    }

    private sealed interface Claimed {
        data class File(val target: FileTarget) : Claimed
        data class Directory(val path: Path) : Claimed
    }

    private fun claim(cwd: Path, argument: String): Claimed? {
        if (argument.isBlank()) return null
        val peeled = peelLocation(argument)
        val written = try {
            Path.of(peeled.path)
        } catch (_: InvalidPathException) {
            null
        }
        if (written != null) {
            val onDisk = (if (written.isAbsolute) written else cwd.resolve(written)).normalize()
            if (Files.isDirectory(onDisk)) return Claimed.Directory(onDisk)
            if (Files.isRegularFile(onDisk)) {
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(onDisk) ?: return null
                return claimFile(file, peeled)
            }
            // An absolute path that is not there is simply not there: looking its basename up in the
            // project would open a different file than the one the user named.
            if (written.isAbsolute) return null
        }
        val location = FileLocation(peeled.path, peeled.line ?: 1, peeled.column)
        val found = ReadAction.computeBlocking<VirtualFile?, RuntimeException> { resolver.resolve(location, null) } ?: return null
        return claimFile(found, peeled)
    }

    /** A binary file is macOS's business — a PDF or a PNG belongs in Preview, not in the editor. */
    private fun claimFile(file: VirtualFile, peeled: Peeled): Claimed? {
        if (file.fileType.isBinary && !openUnknownFileTypes) return null
        return Claimed.File(FileTarget(file, peeled.line, peeled.column))
    }

    private data class Peeled(val path: String, val line: Int?, val column: Int?)

    /**
     * Splits `path:line[:col]` off an argument. [FileLocationParser] stays the definition of the syntax and
     * gets the first look; [LOOSE_LOCATION] then covers the paths its charset excludes on purpose — a shell
     * argument is already one token, so it may well hold spaces or a newline.
     */
    private fun peelLocation(argument: String): Peeled {
        FileLocationParser.parse(argument)
            .singleOrNull { it.range.startOffset == 0 && it.range.endOffset == argument.length }
            ?.let { return Peeled(it.location.path, it.location.line, it.location.column) }
        val match = LOOSE_LOCATION.matchEntire(argument) ?: return Peeled(argument, null, null)
        val line = match.groupValues[2].toInt().takeIf { it >= 1 } ?: return Peeled(argument, null, null)
        return Peeled(match.groupValues[1], line, match.groups[3]?.value?.toInt()?.takeIf { it >= 1 })
    }

    private companion object {
        private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://""")
        private val LOOSE_LOCATION = Regex("""^(.+?):(\d{1,6})(?::(\d{1,5}))?$""")
    }
}
