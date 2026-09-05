package com.pronskiy.agenstorm.core

/** One internal file type name per line; blanks and duplicates are dropped. */
internal fun parseAllowList(text: String): MutableList<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()

internal fun formatAllowList(names: List<String>): String = names.joinToString("\n")
