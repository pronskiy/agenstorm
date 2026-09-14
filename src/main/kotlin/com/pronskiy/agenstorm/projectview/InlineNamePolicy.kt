package com.pronskiy.agenstorm.projectview

import com.intellij.util.PathUtilRt

/**
 * What the inline field is naming. [allowsPath] is whether separators in it mean folders: creating something
 * can put it anywhere below the target folder, while renaming and duplicating only ever produce a name.
 */
enum class InlineNameKind(val allowsPath: Boolean) {
    NEW_FILE(allowsPath = true),
    NEW_DIRECTORY(allowsPath = true),
    RENAME(allowsPath = false),
    DUPLICATE(allowsPath = false),
}

/**
 * What was typed, taken apart: `src/Http/Client.php` is two parent folders and a name.
 *
 * [trailingSeparator] is how `foo/` asks for a folder from the New File field — the platform's own
 * `CreateFileAction` reads a trailing slash the same way.
 */
data class TypedPath(
    val parents: List<String>,
    val name: String,
    val trailingSeparator: Boolean,
)

/** Why a typed name cannot be used. [arg] is the offending segment where one is worth showing. */
sealed interface NameVerdict {

    data object Ok : NameVerdict

    data class Invalid(val messageKey: String, val arg: String? = null) : NameVerdict
}

/**
 * Step O1.1. Everything the inline field can decide on its own: whether what has been typed is a usable name,
 * how it splits into folders and a name, and how much of it to select when the field opens.
 *
 * Deliberately free of the platform beyond [PathUtilRt], which is a pure static utility — so this is the one
 * part of the epic that is testable without an IDE fixture. Anything that needs the file system (does the target
 * already exist two folders down?) is the caller's, and fails with a balloon rather than a red field.
 */
object InlineNamePolicy {

    const val ERROR_EMPTY: String = "projectview.error.empty"
    const val ERROR_PATH_NOT_ALLOWED: String = "projectview.error.pathNotAllowed"
    const val ERROR_EMPTY_SEGMENT: String = "projectview.error.emptySegment"
    const val ERROR_DOT_SEGMENT: String = "projectview.error.dotSegment"
    const val ERROR_INVALID_NAME: String = "projectview.error.invalidName"
    const val ERROR_EXISTS: String = "projectview.error.exists"

    private val SEPARATORS = charArrayOf('/', '\\')

    /**
     * Takes [typed] apart on either separator. A trailing separator is recorded rather than kept, so
     * `src/Http/` is the same two parents as `src/Http/x` minus the name.
     */
    fun split(typed: String): TypedPath {
        val trimmed = typed.trim()
        val trailing = trimmed.isNotEmpty() && trimmed.last() in SEPARATORS
        val body = if (trailing) trimmed.dropLast(1) else trimmed
        val segments = body.split(*SEPARATORS)
        return TypedPath(
            parents = segments.dropLast(1),
            name = segments.last(),
            trailingSeparator = trailing,
        )
    }

    /**
     * Whether [typed] can be used for [kind]. [siblingNames] are the names already in the directory the new
     * element goes into — for a rename, without the element's own name, so retyping it is not a collision.
     *
     * Only a name with no parent folders is checked against [siblingNames]: once a path has folders in it,
     * what is already inside them is not something the field knows.
     */
    fun validate(kind: InlineNameKind, typed: String, siblingNames: Set<String>): NameVerdict {
        val trimmed = typed.trim()
        if (trimmed.isEmpty()) return NameVerdict.Invalid(ERROR_EMPTY)

        if (!kind.allowsPath && trimmed.any { it in SEPARATORS }) {
            return NameVerdict.Invalid(ERROR_PATH_NOT_ALLOWED)
        }

        val path = split(trimmed)
        val segments = path.parents + path.name
        if (segments.any { it.isEmpty() }) return NameVerdict.Invalid(ERROR_EMPTY_SEGMENT)
        segments.firstOrNull { it == "." || it == ".." }?.let { return NameVerdict.Invalid(ERROR_DOT_SEGMENT, it) }
        // Strict: rejects `<>:"/\\|?*;` and control characters, so a name typed on macOS still works for
        // whoever clones the repo on Windows. Spaces and dots are allowed either way.
        segments.firstOrNull { !PathUtilRt.isValidFileName(it, true) }
            ?.let { return NameVerdict.Invalid(ERROR_INVALID_NAME, it) }

        if (path.parents.isEmpty() && path.name in siblingNames) {
            return NameVerdict.Invalid(ERROR_EXISTS, path.name)
        }
        return NameVerdict.Ok
    }

    /**
     * How much of [name] to select when the field opens: the part before the extension, so typing replaces the
     * name and keeps the `.php`. A dot file has no extension to protect, and neither does a folder.
     */
    fun selectionEnd(name: String, isDirectory: Boolean): Int {
        if (isDirectory) return name.length
        val dot = name.lastIndexOf('.')
        return if (dot > 0) dot else name.length
    }
}
