package com.pronskiy.agenstorm.terminal.enhance.viewer

import com.pronskiy.agenstorm.terminal.enhance.RenderMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Step I2.2. Turns a block's text into a [PayloadNode] tree. A `json` rule parses JSON; a `tree` rule is
 * sniffed from its first line — `var_dump`, `print_r`, `var_export` or JSON — so a user's own `tree` rule needs
 * no format field. Whatever does not parse is shown as it is, one node per line: the viewer never refuses to open.
 *
 * The PHP parsers are line-based and tolerant: a line they do not understand becomes a plain node in place and
 * parsing carries on, which is what hand-written and half-scrolled output needs.
 */
object PayloadTreeParsers {

    private val VAR_DUMP_OPEN = Regex("""^(?:array\(\d+\)|object\([^)]*\)(?:#\d+)?\s*\(\d+\)|enum\([^)]*\))\s*\{$""")
    private val VAR_DUMP_KEY = Regex("""^(\[.+])=>$""")
    private val PRINT_R_OPEN = Regex("""^(?:Array|[A-Za-z_\\][\w\\]* Object)$""")
    private val PRINT_R_ENTRY = Regex("""^\[(.*?)] => (.*)$""")
    private val VAR_EXPORT_OPEN = Regex("""^(?:array \(|\(object\) array\(|\\?[A-Za-z_][\w\\]*::__set_state\(array\()$""")
    private val VAR_EXPORT_CLOSE = Regex("""^\)+,?$""")
    private val VAR_EXPORT_ENTRY = Regex("""^(.+?) =>\s*(.*)$""")

    fun parse(payload: String, render: RenderMode): PayloadNode = when (render) {
        RenderMode.JSON -> parseJson(payload) ?: raw(payload)
        RenderMode.TREE -> sniff(payload) ?: raw(payload)
        RenderMode.FOLD -> raw(payload)
    }

    /** Picks the parser from the first non-blank line; null when none applies. */
    fun sniff(payload: String): PayloadNode? {
        val first = payload.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return when {
            VAR_DUMP_OPEN.matches(first) -> parseVarDump(payload)
            PRINT_R_OPEN.matches(first) -> parsePrintR(payload)
            VAR_EXPORT_OPEN.matches(first) -> parseVarExport(payload)
            first.startsWith("{") || first.startsWith("[") -> parseJson(payload)
            else -> null
        }
    }

    /** The text as it is: one node per non-empty line under a root that names nothing. */
    fun raw(payload: String): PayloadNode =
        PayloadNode(null, "output", payload.lines().filter { it.isNotBlank() }.map { PayloadNode(null, it.trimEnd()) })

    // --- var_dump ------------------------------------------------------------------------------------------------

    fun parseVarDump(payload: String): PayloadNode? {
        val lines = Lines(payload)
        if (lines.peek()?.let(VAR_DUMP_OPEN::matches) != true) return null
        return dumpValue(lines, null)
    }

    private fun dumpValue(lines: Lines, key: String?): PayloadNode? {
        val line = lines.next() ?: return null
        if (!VAR_DUMP_OPEN.matches(line)) return PayloadNode(key, line)
        val children = ArrayList<PayloadNode>()
        while (true) {
            val next = lines.peek() ?: break
            if (next == "}") {
                lines.next()
                break
            }
            val entry = VAR_DUMP_KEY.matchEntire(next)
            if (entry == null) {
                lines.next()
                children += PayloadNode(null, next)
                continue
            }
            lines.next()
            children += dumpValue(lines, entry.groupValues[1]) ?: break
        }
        return PayloadNode(key, line.removeSuffix("{").trim(), children)
    }

    // --- print_r -------------------------------------------------------------------------------------------------

    fun parsePrintR(payload: String): PayloadNode? {
        val lines = Lines(payload)
        val head = lines.next() ?: return null
        if (!PRINT_R_OPEN.matches(head)) return null
        return printRContainer(lines, null, head)
    }

    private fun printRContainer(lines: Lines, key: String?, head: String): PayloadNode? {
        if (lines.next() != "(") return null
        val children = ArrayList<PayloadNode>()
        while (true) {
            val line = lines.next() ?: break
            if (line == ")") break
            val entry = PRINT_R_ENTRY.matchEntire(line)
            if (entry == null) {
                children += PayloadNode(null, line)
                continue
            }
            val (k, value) = entry.destructured
            children += if (PRINT_R_OPEN.matches(value)) printRContainer(lines, "[$k]", value) ?: break else PayloadNode("[$k]", value)
        }
        return PayloadNode(key, head, children)
    }

    // --- var_export ----------------------------------------------------------------------------------------------

    fun parseVarExport(payload: String): PayloadNode? {
        val lines = Lines(payload)
        val head = lines.next() ?: return null
        if (!VAR_EXPORT_OPEN.matches(head)) return null
        return exportContainer(lines, null, head)
    }

    private fun exportContainer(lines: Lines, key: String?, head: String): PayloadNode {
        val children = ArrayList<PayloadNode>()
        while (true) {
            val line = lines.next() ?: break
            if (VAR_EXPORT_CLOSE.matches(line)) break
            val entry = VAR_EXPORT_ENTRY.matchEntire(line)
            if (entry == null) {
                children += PayloadNode(null, line.removeSuffix(","))
                continue
            }
            val (k, value) = entry.destructured
            children += when {
                // `'a' =>` alone: the value starts on the next line, a container or a scalar of its own.
                value.isEmpty() -> {
                    val next = lines.next() ?: break
                    if (VAR_EXPORT_OPEN.matches(next)) exportContainer(lines, k, next) else PayloadNode(k, next.removeSuffix(","))
                }
                VAR_EXPORT_OPEN.matches(value) -> exportContainer(lines, k, value)
                else -> PayloadNode(k, value.removeSuffix(","))
            }
        }
        return PayloadNode(key, exportHead(head), children)
    }

    /** `array (` → `array`, `(object) array(` → `(object) array`, `\Foo::__set_state(array(` → `\Foo::__set_state`. */
    private fun exportHead(head: String): String =
        head.removeSuffix("(").trimEnd().let { if (it.endsWith("(array")) it.removeSuffix("(array") else it }

    // --- JSON ----------------------------------------------------------------------------------------------------

    fun parseJson(payload: String): PayloadNode? =
        try {
            jsonNode(null, Json.parseToJsonElement(payload.trim()))
        } catch (e: Exception) {
            null
        }

    private fun jsonNode(key: String?, element: JsonElement): PayloadNode = when (element) {
        is JsonObject -> PayloadNode(key, "{${element.size}}", element.entries.map { (k, v) -> jsonNode("\"$k\"", v) })
        is JsonArray -> PayloadNode(key, "[${element.size}]", element.mapIndexed { i, v -> jsonNode("[$i]", v) })
        is JsonPrimitive -> PayloadNode(key, if (element.isString) "\"${element.content}\"" else element.content)
        else -> PayloadNode(key, element.toString())
    }

    /** Trimmed, non-blank lines with one-line lookahead. */
    private class Lines(text: String) {
        private val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        private var index = 0
        fun peek(): String? = lines.getOrNull(index)
        fun next(): String? = lines.getOrNull(index)?.also { index++ }
    }
}
