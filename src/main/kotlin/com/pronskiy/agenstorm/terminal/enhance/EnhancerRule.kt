package com.pronskiy.agenstorm.terminal.enhance

import java.util.regex.MatchResult
import java.util.regex.Pattern

/** How a matched block is shown: folded to its summary, or opened in the structured viewer as a tree or as JSON. */
enum class RenderMode { FOLD, TREE, JSON }

/**
 * Step I1.1. One rule of the terminal output enhancer, as the user wrote it in a JSON file — validated, with its
 * regexes compiled, so nothing downstream has to check anything again.
 *
 * [start] matches the first line of a block. [end] closes it; without one the block is that single line.
 * [summary] is a template over [start]'s capture groups (`{1}`, `{2}`, …, `{0}` for the whole match and `{line}`
 * for the whole first line) that becomes the fold placeholder. [maxLines] caps how far past [start] a block may
 * run before it is given up on.
 */
class EnhancerRule(
    val id: String,
    val start: Pattern,
    val end: Pattern?,
    val render: RenderMode,
    val summary: String,
    val enabled: Boolean,
    val maxLines: Int,
    /** Where the rule came from — the file's name, or `built-in` — for balloons and the settings table. */
    val source: String,
) {
    /** True when the block is just the line [start] matched. */
    val isSingleLine: Boolean get() = end == null

    /**
     * The placeholder for a block whose first [line] [start] matched as [match]; a group that did not take part
     * is empty.
     */
    fun summaryFor(match: MatchResult, line: CharSequence): String {
        val withLine = summary.replace(LINE_REF, line.toString())
        return GROUP_REF.matcher(withLine).replaceAll { m ->
            val group = m.group(1).toInt()
            val text = if (group <= match.groupCount()) match.group(group) ?: "" else ""
            java.util.regex.Matcher.quoteReplacement(text)
        }
    }

    override fun toString(): String = "EnhancerRule($id from $source)"

    companion object {
        /** `{n}` in a summary template. */
        val GROUP_REF: Pattern = Pattern.compile("\\{(\\d+)}")

        /** The whole first line, in a summary template. */
        const val LINE_REF = "{line}"

        /** The [source] of a rule that ships with the plugin. */
        const val BUILT_IN_SOURCE = "built-in"

        const val DEFAULT_MAX_LINES = 500
        const val DEFAULT_SUMMARY = LINE_REF
    }
}
