package com.pronskiy.agenstorm.terminal

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Step K1.4. Decides what the IDE does with one `$EDITOR` invocation — the whole of this feature's routing,
 * so it is where its tests live.
 *
 * Strict on purpose, and the opposite of [OpenCommandRouter] in one way. `open` is a command a person types,
 * so it guesses: it peels a `:line:col` off, it looks a bare name up in the project. `$EDITOR` is called by a
 * program that has just written the file it passes, so there is nothing to guess — and an invocation that
 * does not look like "here is one file" (`vi +42 notes.txt`, `$EDITOR -R log`, no arguments at all) means
 * something this bridge does not model, which the shim's real editor will understand better than we would.
 */
object EditCommandRouter {

    /** What the IDE should do with the invocation; [Decision.Decline] sends the shim to a real editor. */
    sealed interface Decision {
        /** The IDE takes it: [path] is absolute, normalized, on disk and writable. */
        data class Edit(val path: Path) : Decision
        data class Decline(val reason: String) : Decision
    }

    fun route(cwd: Path, argv: List<String>): Decision {
        val argument = argv.singleOrNull()
            ?: return Decision.Decline("one file is what an editor is called with, not ${argv.size} arguments")
        // `-w`, `-R`, and vi's `+42`: an option means the caller expects an editor we are not.
        if (argument.startsWith("-") || argument.startsWith("+")) return Decision.Decline("option $argument")
        val written = try {
            Path.of(argument)
        } catch (_: InvalidPathException) {
            return Decision.Decline("not a path: $argument")
        }
        val path = (if (written.isAbsolute) written else cwd.resolve(written)).normalize()
        // The caller writes the file before it hands it over, so anything else is a shape we do not model:
        // a directory, a file to be created, a device. Opening a read-only file would be worse than
        // declining — the caller would wait for an edit that can never be saved.
        if (!Files.isRegularFile(path)) return Decision.Decline("not a file on disk: $path")
        if (!Files.isWritable(path)) return Decision.Decline("read-only: $path")
        return Decision.Edit(path)
    }
}
