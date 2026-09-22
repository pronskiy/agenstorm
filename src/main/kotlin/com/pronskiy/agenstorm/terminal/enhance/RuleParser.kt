package com.pronskiy.agenstorm.terminal.enhance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * A rule file that could not be read. [source] is the file, [field] the offending key (null when the file as a
 * whole is the problem) and [expected] what would have been accepted — the three things a person fixing the file
 * by hand needs, which is why they are fields and not just a message.
 */
class RuleParseException(val source: String, val field: String?, val expected: String) :
    Exception(if (field == null) "$source: $expected" else "$source: field \"$field\" — $expected")

/**
 * Step I1.1. JSON in, one validated [EnhancerRule] out, or a [RuleParseException] that names the file, the field
 * and what was expected. The file is read by hand from a `JsonElement` rather than through `@Serializable`: the
 * messages have to be precise for humans, and the serialization compiler plugin would be one more moving part
 * for a six-field object.
 *
 * ```json
 * { "id": "php-var-dump", "start": "^(array|object)\\((\\d+)\\)\\s*\\{$",
 *   "end": "^\\}$", "render": "tree", "summary": "{1}({2}) …", "maxLines": 500 }
 * ```
 */
object RuleParser {

    private val KNOWN_FIELDS = setOf("id", "start", "end", "render", "summary", "enabled", "maxLines")
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    /** Parses one rule file's [text]; [source] is the name to report, typically the file name. */
    fun parse(text: String, source: String): EnhancerRule {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw RuleParseException(source, null, "valid JSON (${e.message?.lineSequence()?.first()?.trim() ?: "unreadable"})")
        }
        val obj = root as? JsonObject ?: throw RuleParseException(source, null, "a JSON object with the rule's fields at the top level")
        obj.keys.firstOrNull { it !in KNOWN_FIELDS }?.let {
            throw RuleParseException(source, it, "no such field; the fields are ${KNOWN_FIELDS.joinToString()}")
        }

        val id = string(obj, "id", source, required = true)!!
        if (!ID.matches(id)) throw RuleParseException(source, "id", "letters, digits, '.', '_' or '-', starting with a letter or digit")

        val start = regex(obj, "start", source, required = true)!!
        val end = regex(obj, "end", source, required = false)

        val render = string(obj, "render", source, required = false)?.let { name ->
            RenderMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw RuleParseException(source, "render", "one of ${RenderMode.entries.joinToString { it.name.lowercase() }}")
        } ?: RenderMode.FOLD

        val summary = string(obj, "summary", source, required = false) ?: EnhancerRule.DEFAULT_SUMMARY
        val groups = start.matcher("").groupCount()
        val refs = EnhancerRule.GROUP_REF.matcher(summary)
        while (refs.find()) {
            val n = refs.group(1).toIntOrNull() ?: Int.MAX_VALUE
            if (n > groups) throw RuleParseException(source, "summary", "{$n} refers to a group \"start\" does not have; it has $groups")
        }

        val enabled = obj["enabled"]?.let {
            (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.content?.toBooleanStrictOrNull()
                ?: throw RuleParseException(source, "enabled", "true or false")
        } ?: true

        val maxLines = obj["maxLines"]?.let {
            (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.content?.toIntOrNull()?.takeIf { n -> n >= 1 }
                ?: throw RuleParseException(source, "maxLines", "a whole number of at least 1")
        } ?: EnhancerRule.DEFAULT_MAX_LINES

        return EnhancerRule(id, start, end, render, summary, enabled, maxLines, source)
    }

    private fun string(obj: JsonObject, field: String, source: String, required: Boolean): String? {
        val element: JsonElement = obj[field] ?: if (required) throw RuleParseException(source, field, "a string; the field is required") else return null
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw RuleParseException(source, field, "a string")
        if (required && primitive.content.isEmpty()) throw RuleParseException(source, field, "a non-empty string")
        return primitive.content
    }

    private fun regex(obj: JsonObject, field: String, source: String, required: Boolean): Pattern? {
        val text = string(obj, field, source, required) ?: return null
        return try {
            Pattern.compile(text)
        } catch (e: PatternSyntaxException) {
            throw RuleParseException(source, field, "a valid regular expression (${e.description} near index ${e.index})")
        }
    }
}
