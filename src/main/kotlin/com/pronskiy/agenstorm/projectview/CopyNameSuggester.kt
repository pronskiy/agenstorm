package com.pronskiy.agenstorm.projectview

/**
 * Step O2.1. The name a duplicate opens with: `Client.php` becomes `Client 2.php`, and `Client 2.php`
 * becomes `Client 3.php` rather than `Client 2 2.php`.
 *
 * The extension is kept out of the counted part, using the same "last dot, but not a leading one" rule as
 * [InlineNamePolicy.selectionEnd] — so the field opens with `Client 2` selected and `.php` safe behind it.
 */
object CopyNameSuggester {

    /** Where a counted suffix would go, and what is already there. */
    private val COUNTED = Regex("^(.*?) (\\d+)$")

    /**
     * The first name not in [siblingNames], starting from the source's own name with a counter added.
     * [siblingNames] is the folder the copy lands in, so the source itself is normally among them.
     */
    fun suggest(name: String, siblingNames: Set<String>, isDirectory: Boolean): String {
        val stemEnd = InlineNamePolicy.selectionEnd(name, isDirectory)
        val stem = name.substring(0, stemEnd)
        val extension = name.substring(stemEnd)

        val counted = COUNTED.matchEntire(stem)
        val base = counted?.groupValues?.get(1) ?: stem
        var counter = counted?.groupValues?.get(2)?.toIntOrNull()?.plus(1) ?: 2

        while (true) {
            val candidate = "$base $counter$extension"
            if (candidate !in siblingNames) return candidate
            counter++
        }
    }
}
