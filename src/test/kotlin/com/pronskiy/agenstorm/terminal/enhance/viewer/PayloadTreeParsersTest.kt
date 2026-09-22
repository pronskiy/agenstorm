package com.pronskiy.agenstorm.terminal.enhance.viewer

import com.pronskiy.agenstorm.terminal.enhance.RenderMode
import junit.framework.TestCase
import java.io.File

/** Step I2.2: the four formats become the same tree, the format is sniffed for `tree` rules, and nothing refuses to open. */
class PayloadTreeParsersTest : TestCase() {

    private fun fixture(name: String) = File("src/test/testData/terminal/enhance/$name").readText()

    /** The block a rule would hand over: from the opener to the closing line, without the prompt lines around it. */
    private fun block(name: String, first: String, last: String): String {
        val text = fixture(name)
        val start = text.indexOf(first)
        val end = text.indexOf(last, start) + last.length
        return text.substring(start, end)
    }

    fun testVarDumpNestedArraysAndAnObject() {
        val root = PayloadTreeParsers.parse(block("var-dump.txt", "array(2) {", "\n}"), RenderMode.TREE)

        assertEquals("array(2)", root.label)
        assertEquals(listOf("[\"a\"] => array(2)", "[\"b\"] => object(stdClass)#1 (0)"), root.children.map { it.label })
        assertEquals(listOf("[0] => int(1)", "[1] => int(2)"), root.children[0].children.map { it.label })
        assertTrue(root.children[1].children.isEmpty())
    }

    fun testPrintRNestedArrayAndObject() {
        val root = PayloadTreeParsers.parse(block("print-r.txt", "Array", "\n)"), RenderMode.TREE)

        assertEquals("Array", root.label)
        assertEquals(listOf("[a] => Array", "[b] => stdClass Object"), root.children.map { it.label })
        assertEquals(listOf("[0] => 1"), root.children[0].children.map { it.label })
    }

    fun testVarExportNestedArrayAndObject() {
        val root = PayloadTreeParsers.parse(fixture("var-export.txt").trimEnd(), RenderMode.TREE)

        assertEquals("array", root.label)
        assertEquals(listOf("'a' => array", "'b' => (object) array"), root.children.map { it.label })
        assertEquals(listOf("0 => 1"), root.children[0].children.map { it.label })
    }

    fun testVarExportSetState() {
        val text = "\\App\\Foo::__set_state(array(\n   'x' => 1,\n   'y' => 'two',\n))"

        val root = PayloadTreeParsers.parse(text, RenderMode.TREE)

        assertEquals("\\App\\Foo::__set_state", root.label)
        assertEquals(listOf("'x' => 1", "'y' => 'two'"), root.children.map { it.label })
    }

    fun testJsonObjectsArraysAndScalars() {
        val root = PayloadTreeParsers.parse("""{"level":"info","port":8080,"tags":["a",null,true],"nested":{}}""", RenderMode.JSON)

        assertEquals("{4}", root.label)
        assertEquals(listOf("\"level\" => \"info\"", "\"port\" => 8080", "\"tags\" => [3]", "\"nested\" => {0}"), root.children.map { it.label })
        assertEquals(listOf("[0] => \"a\"", "[1] => null", "[2] => true"), root.children[2].children.map { it.label })
    }

    fun testATreeRuleSniffsJsonToo() {
        val root = PayloadTreeParsers.parse("[1, 2]", RenderMode.TREE)

        assertEquals("[2]", root.label)
    }

    fun testWhatDoesNotParseIsShownLineByLine() {
        val root = PayloadTreeParsers.parse("first line\n\n  second line  \n", RenderMode.TREE)

        assertEquals("output", root.label)
        // Indentation is kept: it is what shape half-parsed output still has.
        assertEquals(listOf("first line", "  second line"), root.children.map { it.label })
        assertEquals(listOf("first line", "second line"), PayloadTreeParsers.parse("first line\nsecond line", RenderMode.JSON).children.map { it.label })
    }

    fun testAnUnexpectedLineInsideADumpBecomesANodeAndParsingGoesOn() {
        val text = "array(2) {\n  [\"a\"]=>\n  int(1)\n  garbage here\n  [\"b\"]=>\n  int(2)\n}"

        val root = PayloadTreeParsers.parse(text, RenderMode.TREE)

        assertEquals(listOf("[\"a\"] => int(1)", "garbage here", "[\"b\"] => int(2)"), root.children.map { it.label })
    }

    fun testSubtreeTextIndentsTwoSpacesPerLevel() {
        val root = PayloadTreeParsers.parse("array(1) {\n  [\"a\"]=>\n  array(1) {\n    [0]=>\n    int(1)\n  }\n}", RenderMode.TREE)

        assertEquals("array(1)\n  [\"a\"] => array(1)\n    [0] => int(1)\n", root.subtreeText())
    }
}
