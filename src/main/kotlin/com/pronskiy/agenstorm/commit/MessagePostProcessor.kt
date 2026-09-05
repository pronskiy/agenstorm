package com.pronskiy.agenstorm.commit

/**
 * Normalises raw model output into `subject`, blank line, `body`: drops code fences and chatty
 * "Here is the commit message:" prefixes, unquotes a single quoted line, wraps body lines at word
 * boundaries, and keeps only the first paragraph when the body is disabled. An over-long subject is
 * left alone on purpose; the platform's commit-message inspection already flags it.
 */
object MessagePostProcessor {

    private val FENCE_OPEN = Regex("^```[\\w-]*\\s*$")
    private val FENCE_CLOSE = Regex("^```\\s*$")
    private val CHATTY = "(?:here(?:'s| is)(?: a| the| your| my)?\\s+)?(?:suggested |proposed |possible |improved )?commit message"
    private val PREFIX_LINE = Regex("^$CHATTY\\s*:?\\s*$", RegexOption.IGNORE_CASE)
    private val PREFIX_INLINE = Regex("^$CHATTY\\s*:\\s*", RegexOption.IGNORE_CASE)

    fun process(raw: String, bodyEnabled: Boolean = true, wrapAt: Int = 72): String {
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
        return subject + "\n\n" + body.flatMap { wrap(it, wrapAt) }.joinToString("\n")
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

    /** Greedy word wrap that keeps the line's indentation and never splits a single over-long token. */
    private fun wrap(line: String, width: Int): List<String> {
        if (line.length <= width) return listOf(line)
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val words = line.trim().split(Regex("\\s+"))
        val out = ArrayList<String>()
        val current = StringBuilder(indent)
        for (word in words) {
            val hasContent = current.length > indent.length
            if (hasContent && current.length + 1 + word.length > width) {
                out += current.toString()
                current.setLength(0)
                current.append(indent)
            }
            if (current.length > indent.length) current.append(' ')
            current.append(word)
        }
        if (current.length > indent.length) out += current.toString()
        return out
    }
}
