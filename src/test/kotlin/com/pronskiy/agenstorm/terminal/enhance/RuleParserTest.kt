package com.pronskiy.agenstorm.terminal.enhance

import junit.framework.TestCase

/** Step I1.1: a rule file becomes a compiled rule, and every way of getting it wrong names the file, the field and what was expected. */
class RuleParserTest : TestCase() {

    private val varDump = """
        { "id": "php-var-dump", "start": "^(array|object)\\((\\d+)\\)\\s*\\{$",
          "end": "^\\}$", "render": "tree", "summary": "{1}({2}) …", "maxLines": 500 }
    """.trimIndent()

    private fun failure(json: String, source: String = "rule.json"): RuleParseException =
        try {
            RuleParser.parse(json, source)
            fail("parsed: $json")
            throw IllegalStateException()
        } catch (e: RuleParseException) {
            e
        }

    fun testTheSpecExampleParsesCompletely() {
        val rule = RuleParser.parse(varDump, "php-var-dump.json")

        assertEquals("php-var-dump", rule.id)
        assertTrue(rule.start.matcher("array(3) {").matches())
        assertTrue(rule.end!!.matcher("}").matches())
        assertFalse(rule.isSingleLine)
        assertEquals(RenderMode.TREE, rule.render)
        assertEquals("{1}({2}) …", rule.summary)
        assertTrue(rule.enabled)
        assertEquals(500, rule.maxLines)
        assertEquals("php-var-dump.json", rule.source)
    }

    fun testTheSummaryIsFilledFromTheStartGroups() {
        val rule = RuleParser.parse(varDump, "r.json")
        val m = rule.start.matcher("object(12) {")
        assertTrue(m.matches())

        assertEquals("object(12) …", rule.summaryFor(m, "object(12) {"))
    }

    fun testAGroupThatDidNotTakePartIsEmptyAndDollarSignsSurvive() {
        val rule = RuleParser.parse("""{ "id": "r", "start": "^(a)|(b)$", "summary": "[{1}|{2}] {0} ${'$'}1" }""", "r.json")
        val m = rule.start.matcher("b")
        assertTrue(m.matches())

        assertEquals("[|b] b ${'$'}1", rule.summaryFor(m, "b"))
    }

    fun testDefaults() {
        val rule = RuleParser.parse("""{ "id": "one-line", "start": "^Fatal error:" }""", "r.json")

        assertNull(rule.end)
        assertTrue(rule.isSingleLine)
        assertEquals(RenderMode.FOLD, rule.render)
        assertEquals("{line}", rule.summary)
        assertTrue(rule.enabled)
        assertEquals(EnhancerRule.DEFAULT_MAX_LINES, rule.maxLines)
        // The whole line, not just the text the regex covered.
        val m = rule.start.matcher("Fatal error: x").also { assertTrue(it.find()) }
        assertEquals("Fatal error: x", rule.summaryFor(m, "Fatal error: x"))
    }

    fun testRenderIsCaseInsensitiveAndEnabledCanBeFalse() {
        val rule = RuleParser.parse("""{ "id": "r", "start": "x", "render": "JSON", "enabled": false }""", "r.json")

        assertEquals(RenderMode.JSON, rule.render)
        assertFalse(rule.enabled)
    }

    fun testInvalidJsonNamesTheFileAndNoField() {
        val e = failure("{ \"id\": ", "broken.json")

        assertEquals("broken.json", e.source)
        assertNull(e.field)
        assertTrue(e.message!!, e.message!!.startsWith("broken.json: valid JSON"))
    }

    fun testATopLevelArrayIsRefused() {
        val e = failure("""[{ "id": "r", "start": "x" }]""")

        assertNull(e.field)
        assertTrue(e.expected, e.expected.contains("JSON object"))
    }

    fun testIdIsRequiredNonEmptyAndPlain() {
        assertEquals("id", failure("""{ "start": "x" }""").field)
        assertEquals("id", failure("""{ "id": "", "start": "x" }""").field)
        assertEquals("id", failure("""{ "id": 7, "start": "x" }""").field)
        val e = failure("""{ "id": "has space", "start": "x" }""")
        assertEquals("id", e.field)
        assertTrue(e.expected, e.expected.contains("letters, digits"))
        assertEquals("rule.json: field \"id\" — ${e.expected}", e.message)
    }

    fun testStartIsRequiredAndMustCompile() {
        assertEquals("start", failure("""{ "id": "r" }""").field)
        val e = failure("""{ "id": "r", "start": "(unclosed" }""")
        assertEquals("start", e.field)
        assertTrue(e.expected, e.expected.startsWith("a valid regular expression ("))
    }

    fun testEndMustCompileWhenPresent() {
        val e = failure("""{ "id": "r", "start": "x", "end": "[" }""")

        assertEquals("end", e.field)
        assertTrue(e.expected, e.expected.contains("regular expression"))
    }

    fun testRenderMustBeOneOfThree() {
        val e = failure("""{ "id": "r", "start": "x", "render": "table" }""")

        assertEquals("render", e.field)
        assertEquals("one of fold, tree, json", e.expected)
    }

    fun testASummaryCannotReferToAGroupStartDoesNotHave() {
        val e = failure("""{ "id": "r", "start": "^(a)$", "summary": "{2}" }""")

        assertEquals("summary", e.field)
        assertTrue(e.expected, e.expected.contains("{2}") && e.expected.contains("it has 1"))
    }

    fun testMaxLinesIsAPositiveWholeNumber() {
        assertEquals("maxLines", failure("""{ "id": "r", "start": "x", "maxLines": 0 }""").field)
        assertEquals("maxLines", failure("""{ "id": "r", "start": "x", "maxLines": "12" }""").field)
        assertEquals("maxLines", failure("""{ "id": "r", "start": "x", "maxLines": 1.5 }""").field)
        assertEquals(12, RuleParser.parse("""{ "id": "r", "start": "x", "maxLines": 12 }""", "r.json").maxLines)
    }

    fun testEnabledIsABoolean() {
        val e = failure("""{ "id": "r", "start": "x", "enabled": "yes" }""")

        assertEquals("enabled", e.field)
        assertEquals("true or false", e.expected)
    }

    fun testAnUnknownFieldIsAnErrorNamingIt() {
        val e = failure("""{ "id": "r", "start": "x", "sumary": "{0}" }""")

        assertEquals("sumary", e.field)
        assertTrue(e.expected, e.expected.contains("no such field") && e.expected.contains("summary"))
    }
}
