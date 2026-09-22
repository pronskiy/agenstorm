package com.pronskiy.agenstorm.terminal.enhance

import com.pronskiy.agenstorm.terminal.enhance.BlockColorizer.Kind
import junit.framework.TestCase
import java.io.File

/** Step I2.2: the four formats get keys, types, class names, strings, numbers and arrows coloured; a fold block gets nothing. */
class BlockColorizerTest : TestCase() {

    private fun fixture(name: String) = File("src/test/testData/terminal/enhance/$name").readText()

    private fun coloured(text: String, render: RenderMode = RenderMode.TREE): List<Pair<String, Kind>> =
        BlockColorizer.tokens(text, render).map { text.substring(it.range.startOffset, it.range.endOffset) to it.kind }

    fun testVarDump() {
        val text = "array(2) {\n  [\"a\"]=>\n  int(1)\n  [\"b\"]=>\n  object(stdClass)#1 (0) {\n  }\n}"

        val tokens = coloured(text)

        assertEquals(
            listOf("array" to Kind.TYPE, "2" to Kind.NUMBER, "[\"a\"]" to Kind.KEY, "=>" to Kind.ARROW, "int" to Kind.TYPE, "1" to Kind.NUMBER,
                "[\"b\"]" to Kind.KEY, "=>" to Kind.ARROW, "object" to Kind.TYPE, "stdClass" to Kind.CLASS, "1" to Kind.NUMBER, "0" to Kind.NUMBER),
            tokens,
        )
    }

    fun testPrintR() {
        val tokens = coloured("Array\n(\n    [a] => 1\n    [b] => stdClass Object\n        (\n        )\n\n)")

        assertEquals(
            listOf("Array" to Kind.TYPE, "[a]" to Kind.KEY, "=>" to Kind.ARROW, "1" to Kind.NUMBER, "[b]" to Kind.KEY, "=>" to Kind.ARROW, "stdClass" to Kind.CLASS, "Object" to Kind.TYPE),
            tokens,
        )
    }

    fun testVarExport() {
        val tokens = coloured("\\App\\Foo::__set_state(array(\n   'x' => 1,\n   'y' => 'two',\n))")

        assertEquals(
            listOf("\\App\\Foo" to Kind.CLASS, "array" to Kind.TYPE, "'x'" to Kind.KEY, "=>" to Kind.ARROW, "1" to Kind.NUMBER, "'y'" to Kind.KEY, "=>" to Kind.ARROW, "'two'" to Kind.STRING),
            tokens,
        )
    }

    fun testJson() {
        val tokens = coloured("""{"level":"info","port":8080,"tags":[true,null,-1.5e3]}""", RenderMode.JSON)

        assertEquals(
            listOf("\"level\"" to Kind.KEY, ":" to Kind.ARROW, "\"info\"" to Kind.STRING, "\"port\"" to Kind.KEY, ":" to Kind.ARROW, "8080" to Kind.NUMBER,
                "\"tags\"" to Kind.KEY, ":" to Kind.ARROW, "true" to Kind.TYPE, "null" to Kind.TYPE, "-1.5e3" to Kind.NUMBER),
            tokens,
        )
    }

    fun testATreeBlockThatIsJsonIsSniffed() {
        assertEquals(listOf("[1, 2]".indexOf('1')), BlockColorizer.tokens("[1, 2]", RenderMode.TREE).map { it.range.startOffset }.take(1))
    }

    fun testAFoldBlockAndUnrecognisedTextGetNothing() {
        assertTrue(BlockColorizer.tokens(fixture("stack-trace.txt"), RenderMode.FOLD).isEmpty())
        assertTrue(BlockColorizer.tokens("just some words 42", RenderMode.TREE).isEmpty())
    }

    fun testTokensNeverOverlapAndComeInOrder() {
        // The block as the detector hands it over: from the opener, not the prompt line above it.
        val text = fixture("var-dump.txt").let { it.substring(it.indexOf("array(2) {"), it.lastIndexOf("}") + 1) }
        val tokens = BlockColorizer.tokens(text, RenderMode.TREE)

        for ((a, b) in tokens.zipWithNext()) assertTrue("$a before $b", a.range.endOffset <= b.range.startOffset)
        assertTrue(tokens.size > 10)
    }
}
