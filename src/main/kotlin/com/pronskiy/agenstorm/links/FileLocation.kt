package com.pronskiy.agenstorm.links

import com.intellij.openapi.util.TextRange

/**
 * A `path:line[:column]` location exactly as written in text. [line] and [column] are 1-based;
 * [path] is unresolved (relative or absolute) and may start with `./`, `../` or `/`.
 */
data class FileLocation(val path: String, val line: Int, val column: Int?)

/** A [FileLocation] found in a piece of text; [range] covers the whole `path:line[:column]` token. */
data class FileLocationMatch(val range: TextRange, val location: FileLocation)
