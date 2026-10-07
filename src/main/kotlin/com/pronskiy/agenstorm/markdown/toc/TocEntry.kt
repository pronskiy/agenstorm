package com.pronskiy.agenstorm.markdown.toc

/** Step W1.1. One heading of a Markdown file: its level (1–6), its text as a reader sees it, its 0-based line and start offset. */
data class TocEntry(val level: Int, val title: String, val line: Int, val offset: Int)
