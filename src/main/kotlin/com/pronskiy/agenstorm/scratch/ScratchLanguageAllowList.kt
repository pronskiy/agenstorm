package com.pronskiy.agenstorm.scratch

import com.intellij.lang.Language
import com.intellij.lang.LanguageUtil

/**
 * The allow-list behind [NewScratchFileAction]: which of the languages the platform would offer stay in the
 * popup, and in which order.
 *
 * An entry names a language — by id or by display name. Only when it names none does it fall back to matching
 * the name of a language's *file type*, which is what a list written for 1.0 holds (`PLAIN_TEXT`, `Markdown`,
 * `PHP`, `JavaScript` were `FileType.name`s). The fallback has to be second, not an alternative: `JavaScript` is
 * both a language and the file type of ECMAScript 6 and ActionScript, so matching both at once would drag the
 * dialects back in — which is exactly what the internal `scratchLanguageFilter` this replaces could not avoid,
 * since it was handed a `FileType` and never saw the language at all (decision 14).
 */
object ScratchLanguageAllowList {

    /** [candidates] filtered to [allowed], in the order [allowed] names them; nothing is listed twice. */
    fun filter(candidates: List<Language>, allowed: List<String>): List<Language> {
        val result = LinkedHashSet<Language>()
        for (entry in allowed) {
            val name = entry.trim()
            if (name.isEmpty()) continue
            val byLanguage = candidates.filter { matchesLanguage(it, name) }
            result += byLanguage.ifEmpty { candidates.filter { matchesFileType(it, name) } }
        }
        return result.toList()
    }

    private fun matchesLanguage(language: Language, entry: String): Boolean =
        entry.equals(language.id, ignoreCase = true) || entry.equals(language.displayName, ignoreCase = true)

    private fun matchesFileType(language: Language, entry: String): Boolean =
        entry.equals(LanguageUtil.getLanguageFileType(language)?.name, ignoreCase = true)
}
