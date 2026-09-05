package com.pronskiy.agenstorm.commit

/**
 * Normalises raw model output into `subject`, blank line, `body`: drops code fences and chatty
 * "Here is the commit message:" prefixes, unquotes a single quoted line, trims trailing whitespace, and
 * keeps only the first paragraph when the body is disabled. Body lines are kept exactly as the model wrote
 * them (no hard wrapping, decision 15). An over-long subject is left alone on purpose; the platform's
 * commit-message inspection already flags it.
 */
object MessagePostProcessor {

    private val FENCE_OPEN = Regex("^```[\\w-]*\\s*$")
    private val FENCE_CLOSE = Regex("^```\\s*$")
    private val CHATTY = "(?:here(?:'s| is)(?: a| the| your| my)?\\s+)?(?:suggested |proposed |possible |improved )?commit message"
    private val PREFIX_LINE = Regex("^$CHATTY\\s*:?\\s*$", RegexOption.IGNORE_CASE)
    private val PREFIX_INLINE = Regex("^$CHATTY\\s*:\\s*", RegexOption.IGNORE_CASE)

    fun process(raw: String, bodyEnabled: Boolean = true): String {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').mapTo(ArrayList()) { it.trimEnd() }
        lines.trimBlankEdges()
        if (lines.isNotEmpty() && FENCE_OPEN.matches(lines.first())) lines.removeAt(0)
        if (lines.isNotEmpty() && FENCE_CLOSE.matches(lines.last())) lines.removeAt(lines.lastIndex)
        lines.trimBlankEdges()
        if (lines.isNotEmpty() && PREFIX_LINE.matches(lines.first())) {
            lines.removeAt(0)
            lines.trimBlankEdges()
        } else if (lines.isNotEmpty()) {
            lines[0] = lines[0].replace(PREFIX_INLINE, "")
        }
        if (lines.isEmpty()) return ""
        if (lines.size == 1) lines[0] = unquote(lines[0])

        if (!bodyEnabled) return lines.takeWhile { it.isNotBlank() }.joinToString("\n") { it.trim() }

        val subject = lines[0].trim()
        val body = lines.drop(1).dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        if (body.isEmpty()) return subject
        return subject + "\n\n" + body.joinToString("\n")
    }

    private fun MutableList<String>.trimBlankEdges() {
        while (isNotEmpty() && first().isBlank()) removeAt(0)
        while (isNotEmpty() && last().isBlank()) removeAt(lastIndex)
    }

    private fun unquote(line: String): String {
        val trimmed = line.trim()
        for (quote in listOf('"', '`')) {
            if (trimmed.length >= 2 && trimmed.first() == quote && trimmed.last() == quote) return trimmed.substring(1, trimmed.length - 1).trim()
        }
        return trimmed
    }
}
