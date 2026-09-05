package com.pronskiy.agenstorm.links

import com.intellij.openapi.util.TextRange

/**
 * Finds bare `path:line[:column]` tokens (GitHub / compiler style) in arbitrary text.
 *
 * A token is a path that either ends with `.<ext>` (letter first, at most 8 chars) or contains at least
 * one `/`, followed by `:LINE` and optionally `:COLUMN`. Paths never contain spaces. The token must not
 * be glued to a preceding word character, `:`, `/`, `\` or `.` (which rules out URL hosts and
 * `host:port`), and must not be followed by a word character, `:` or `/` (which rules out `10:20:30`
 * and `Foo.php::42`). `Makefile:3` is an accepted miss: no extension and no slash.
 */
object FileLocationParser {

    private val PATTERN = Regex(
        """(?<![\w:/\\.])""" +                                            // not glued to a word, URL or path
            """((?:\.{1,2}/|/)?[\w.\-]+(?:/[\w.\-]+)*\.[A-Za-z][A-Za-z0-9]{0,7}""" + // <path>.<ext>
            """|(?:\.{1,2}/|/)?[\w.\-]+(?:/[\w.\-]+)+)""" +                 // or a path with at least one '/'
            """:(\d{1,6})(?::(\d{1,5}))?""" +                                // :line[:column]
            """(?![\w:/])""",
    )

    /** Non-overlapping matches in text order. Never throws; an empty list means "no locations". */
    fun parse(text: CharSequence): List<FileLocationMatch> =
        PATTERN.findAll(text).mapNotNull { match ->
            val line = match.groupValues[2].toInt()
            if (line < 1) return@mapNotNull null
            val column = match.groups[3]?.value?.toInt()?.takeIf { it >= 1 }
            FileLocationMatch(
                TextRange(match.range.first, match.range.last + 1),
                FileLocation(match.groupValues[1], line, column),
            )
        }.toList()
}
