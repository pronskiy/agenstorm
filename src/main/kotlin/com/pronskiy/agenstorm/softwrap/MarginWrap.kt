package com.pronskiy.agenstorm.softwrap

/**
 * Step V1.1 (decision 95), pure. The width soft wrap may use — the window's, but never past the right margin, VS Code's
 * "bounded" — and which files get it: those on the IDE's *Soft-wrap these files* list.
 */
object MarginWrap {

    /** [visible] pixels, capped at [rightMarginColumns] columns of [spaceWidth] pixels; a margin of 0 or less caps nothing. */
    fun width(visible: Int, rightMarginColumns: Int, spaceWidth: Int): Int =
        if (rightMarginColumns <= 0 || spaceWidth <= 0) visible else minOf(visible, rightMarginColumns * spaceWidth)

    /**
     * Whether [fileName] is on the soft-wrap list [masks] (`*.md; *.txt; *.rst; *.adoc` by default): `;`-separated
     * globs with `*` and `?`, matched whole and ignoring case, as file-type extensions are.
     */
    fun matches(masks: String, fileName: String): Boolean =
        masks.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }.any { glob(it).matches(fileName) }

    private fun glob(mask: String): Regex = Regex(
        buildString {
            for (c in mask) when (c) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(c.toString()))
            }
        },
        RegexOption.IGNORE_CASE,
    )
}
