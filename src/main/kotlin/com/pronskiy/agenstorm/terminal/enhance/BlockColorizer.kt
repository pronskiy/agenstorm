package com.pronskiy.agenstorm.terminal.enhance

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange

/**
 * Step I2.2 (decision 72). Colours a block in place: a `tree` block is sniffed from its first line — `var_dump`,
 * `print_r`, `var_export` or JSON — and a `json` block is JSON; each token gets one of the editor scheme's
 * language colours, so a dump reads like code in the user's own theme. Nothing here parses: a tokenizer pass is
 * enough for colour, and it never refuses a block — text it does not recognise is simply left as it is.
 */
object BlockColorizer {

    enum class Kind(val key: TextAttributesKey) {
        /** `["a"]`, `[0]`, `'a'` before `=>`, and `"a"` before `:`. */
        KEY(DefaultLanguageHighlighterColors.INSTANCE_FIELD),
        /** `int`, `string`, `array`, `object`, `Array`, `Object`, `NULL`, `true`, `false` … */
        TYPE(DefaultLanguageHighlighterColors.KEYWORD),
        /** `stdClass` in `object(stdClass)#1`, `Foo` in `Foo Object` and `Foo::__set_state`. */
        CLASS(DefaultLanguageHighlighterColors.CLASS_NAME),
        STRING(DefaultLanguageHighlighterColors.STRING),
        NUMBER(DefaultLanguageHighlighterColors.NUMBER),
        /** `=>` and `:`. */
        ARROW(DefaultLanguageHighlighterColors.OPERATION_SIGN),
    }

    data class Token(val range: TextRange, val kind: Kind)

    private val VAR_DUMP_OPEN = Regex("""^(?:array\(\d+\)|object\([^)]*\)(?:#\d+)?\s*\(\d+\)|enum\([^)]*\))\s*\{$""")
    private val PRINT_R_OPEN = Regex("""^(?:Array|[A-Za-z_\\][\w\\]* Object)$""")
    private val VAR_EXPORT_OPEN = Regex("""^(?:array \(|\(object\) array\(|\\?[A-Za-z_][\w\\]*::__set_state\(array\()$""")

    private val PHP_CLASSES = listOf(
        Regex("""object\(([^)#]+)\)"""),
        Regex("""^[ \t]*(?:\[[^\]\n]*\] => )?([A-Za-z_\\][\w\\]*) Object$""", RegexOption.MULTILINE),
        Regex("""([A-Za-z_\\][\w\\]*)::__set_state"""),
    )
    private val PHP_TOKENS = Regex(
        """(?<key>^[ \t]*(?:\[[^\]\n]*\]|'(?:[^'\\\n]|\\.)*'|\d+)(?=\s*=>))""" +
            """|(?<str>"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*')""" +
            """|(?<type>\b(?:int|float|double|bool|boolean|string|array|object|enum|resource|NULL|null|true|false|Array|Object|uninitialized|closed resource)\b)""" +
            """|(?<num>(?<![\w.])-?\d+(?:\.\d+)?(?![\w.]))""" +
            """|(?<arrow>=>)""",
        RegexOption.MULTILINE,
    )
    private val JSON_TOKENS = Regex(
        """(?<key>"(?:[^"\\]|\\.)*")(?=\s*:)""" +
            """|(?<str>"(?:[^"\\]|\\.)*")""" +
            """|(?<type>\b(?:true|false|null)\b)""" +
            """|(?<num>-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)""" +
            """|(?<arrow>:)""",
    )

    /** Tokens with offsets relative to [text], never overlapping, in text order. */
    fun tokens(text: CharSequence, render: RenderMode): List<Token> = when (render) {
        RenderMode.FOLD -> emptyList()
        RenderMode.JSON -> json(text)
        RenderMode.TREE -> when (format(text)) {
            Format.JSON -> json(text)
            Format.PHP -> php(text)
            null -> emptyList()
        }
    }

    private enum class Format { PHP, JSON }

    private fun format(text: CharSequence): Format? {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return when {
            VAR_DUMP_OPEN.matches(first) || PRINT_R_OPEN.matches(first) || VAR_EXPORT_OPEN.matches(first) -> Format.PHP
            first.startsWith("{") || first.startsWith("[") -> Format.JSON
            else -> null
        }
    }

    private fun php(text: CharSequence): List<Token> {
        val tokens = ArrayList<Token>()
        for (regex in PHP_CLASSES) {
            for (match in regex.findAll(text)) {
                val group = match.groups[1] ?: continue
                tokens += Token(TextRange(group.range.first, group.range.last + 1), Kind.CLASS)
            }
        }
        return merge(tokens, scan(PHP_TOKENS, text))
    }

    private fun json(text: CharSequence): List<Token> = scan(JSON_TOKENS, text)

    private fun scan(regex: Regex, text: CharSequence): List<Token> {
        val tokens = ArrayList<Token>()
        for (match in regex.findAll(text)) {
            val kind = when {
                match.groups["key"] != null -> Kind.KEY
                match.groups["str"] != null -> Kind.STRING
                match.groups["type"] != null -> Kind.TYPE
                match.groups["num"] != null -> Kind.NUMBER
                else -> Kind.ARROW
            }
            val range = TextRange(match.range.first, match.range.last + 1)
            // A key match starts at the line's indentation; the colour goes on the key itself.
            tokens += if (kind == Kind.KEY) Token(trimStart(text, range), kind) else Token(range, kind)
        }
        return tokens
    }

    private fun trimStart(text: CharSequence, range: TextRange): TextRange {
        var start = range.startOffset
        while (start < range.endOffset && (text[start] == ' ' || text[start] == '\t')) start++
        return TextRange(start, range.endOffset)
    }

    /** [first] wins where the two overlap; the result is in text order. */
    private fun merge(first: List<Token>, second: List<Token>): List<Token> {
        val kept = first.toMutableList()
        for (token in second) {
            if (first.none { it.range.intersectsStrict(token.range) }) kept += token
        }
        return kept.sortedBy { it.range.startOffset }
    }
}
