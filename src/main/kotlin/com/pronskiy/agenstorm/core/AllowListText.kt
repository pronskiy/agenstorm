package com.pronskiy.agenstorm.core

/** The allow-list as edited in the settings page: one name per line, blanks and duplicates dropped. */
internal fun parseAllowList(text: String): MutableList<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()

internal fun formatAllowList(names: List<String>): String = names.joinToString("\n")
