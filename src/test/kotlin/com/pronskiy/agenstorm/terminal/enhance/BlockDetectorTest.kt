package com.pronskiy.agenstorm.terminal.enhance

import junit.framework.TestCase
import java.io.File

/** Step I1.2: blocks out of output, only appended text rescanned, and a hostile regex cancelled inside its budget. */
class BlockDetectorTest : TestCase() {

    private val builtIns = BlockDetector.builtInRules()

    private fun fixture(name: String): String =
        File("src/test/testData/terminal/enhance/$name").readText()

    private fun rule(json: String) = RuleParser.parse(json, "test.json")

    private fun detect(text: String, rules: List<EnhancerRule> = builtIns, from: Int = 0) =
        BlockDetector().detect(text, rules, from)

    private fun String.lineRange(firstLine: String, lastLine: String = firstLine): IntRange {
        val start = indexOf(firstLine).also { assertTrue("no line $firstLine", it >= 0) }
        val last = indexOf(lastLine, start).also { assertTrue("no line $lastLine", it >= 0) }
        return start until last + lastLine.length
    }

    private fun EnhancedBlock.covers(text: String, range: IntRange) {
        assertEquals(text.substring(range.first, range.last + 1), text.substring(this.range.startOffset, this.range.endOffset))
    }

    fun testAllFiveBuiltInsParse() {
        assertEquals(
            listOf("php-var-dump", "php-print-r", "php-var-export", "php-stack-trace", "json-line"),
            builtIns.map { it.id },
        )
    }

    fun testANestedVarDumpIsOneBlockFromTheOuterOpenerToTheOuterBrace() {
        val text = fixture("var-dump.txt")

        val found = detect(text)

        assertEquals(1, found.blocks.size)
        val block = found.blocks.single()
        assertEquals("php-var-dump", block.ruleId)
        block.covers(text, text.lineRange("array(2) {", "\n}"))
        assertEquals("array(2) …", block.summary)
        assertEquals(RenderMode.TREE, block.render)
        assertTrue(block.payload!!.startsWith("array(2) {") && block.payload.endsWith("\n}"))
        assertEquals(text.length, found.resumeFrom)
    }

    fun testPrintR() {
        val text = fixture("print-r.txt")

        val block = detect(text).blocks.single()

        assertEquals("php-print-r", block.ruleId)
        block.covers(text, text.lineRange("Array", "\n)"))
        assertEquals("Array ( … )", block.summary)
    }

    fun testVarExport() {
        val text = fixture("var-export.txt")

        val block = detect(text).blocks.single()

        assertEquals("php-var-export", block.ruleId)
        assertEquals(0, block.range.startOffset)
        assertEquals(text.trimEnd().length, block.range.endOffset)
        assertEquals("array ( … )", block.summary)
    }

    fun testEachJsonLineIsItsOwnSingleLineBlock() {
        val text = fixture("json-line.txt")

        val blocks = detect(text).blocks

        assertEquals(listOf("json-line", "json-line"), blocks.map { it.ruleId })
        blocks[0].covers(text, text.lineRange("""{"level":"info","msg":"listening","port":8080}"""))
        blocks[1].covers(text, text.lineRange("""[{"id":1},{"id":2}]"""))
        assertEquals("JSON {…}", blocks[0].summary)
        assertEquals("JSON […]", blocks[1].summary)
        assertEquals(RenderMode.JSON, blocks[0].render)
        assertEquals("""[{"id":1},{"id":2}]""", blocks[1].payload)
    }

    fun testAStackTraceFoldsFromItsHeaderToMain() {
        val text = fixture("stack-trace.txt")

        val block = detect(text).blocks.single()

        assertEquals("php-stack-trace", block.ruleId)
        block.covers(text, text.lineRange("Stack trace:", "#2 {main}"))
        assertEquals("Stack trace …", block.summary)
        assertNull(block.payload)
    }

    fun testABlockStillOpenAtTheEndIsLeftForTheNextScan() {
        val whole = fixture("var-dump.txt")
        val cut = whole.indexOf("  [\"b\"]=>")
        val truncated = whole.substring(0, cut)

        val first = detect(truncated)

        assertTrue(first.blocks.isEmpty())
        assertEquals(truncated.indexOf("array(2) {"), first.resumeFrom)

        val second = detect(whole, from = first.resumeFrom)
        assertEquals(1, second.blocks.size)
        assertEquals("array(2) …", second.blocks.single().summary)
    }

    fun testFromSkipsWhatWasAlreadyScanned() {
        val text = fixture("json-line.txt")
        val secondLine = text.indexOf("[{")

        val blocks = detect(text, from = secondLine + 3).blocks

        assertEquals(1, blocks.size)
        blocks.single().covers(text, text.lineRange("""[{"id":1},{"id":2}]"""))
    }

    fun testABlockLongerThanMaxLinesIsNotABlock() {
        val short = rule("""{ "id": "brace", "start": "^\\{$", "end": "^\\}$", "maxLines": 3 }""")
        val text = "{\n1\n2\n3\n}\n{\n1\n}\n"

        val found = detect(text, listOf(short))

        assertEquals(1, found.blocks.size)
        found.blocks.single().covers(text, text.lineRange("{\n1\n}"))
        assertEquals(text.length, found.resumeFrom)
    }

    fun testTheFirstRuleWinsAndBlocksNeverOverlap() {
        val a = rule("""{ "id": "a", "start": "^x$", "end": "^y$", "summary": "A" }""")
        val b = rule("""{ "id": "b", "start": "^x$", "summary": "B" }""")
        val text = "x\nx\ny\nx\n"

        val blocks = detect(text, listOf(a, b)).blocks

        assertEquals(listOf("A"), blocks.map { it.summary })
        blocks.single().covers(text, 0 until 5)
        // A rule without an end matches every opener.
        assertEquals(3, detect(text, listOf(b)).blocks.size)
    }

    fun testADisabledRuleIsSkipped() {
        val off = rule("""{ "id": "off", "start": "^Stack trace:$", "enabled": false }""")

        assertTrue(detect(fixture("stack-trace.txt"), listOf(off)).blocks.isEmpty())
    }

    fun testACatastrophicRegexIsCancelledInsideItsBudgetAndTheRuleDisabledOnce() {
        // The textbook (a+)+$ is defused by this JDK's regex engine; this one is not — untimed it runs for hours.
        val hostile = rule("""{ "id": "hostile", "start": "^(.*a){20}$" }""")
        val text = "a".repeat(40) + "b\n" + "Stack trace:\n#0 {main}\n"
        val reported = ArrayList<String>()
        val detector = BlockDetector(budgetMs = 20, onRuleDisabled = { r, why -> reported += "${r.id}: $why" })

        val started = System.nanoTime()
        val found = detector.detect(text, listOf(hostile) + builtIns)
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("took $tookMs ms", tookMs < 2_000)
        assertEquals(setOf("hostile"), detector.disabledRuleIds)
        assertEquals(listOf("hostile: took longer than 20 ms on one line"), reported)
        assertEquals(listOf("php-stack-trace"), found.blocks.map { it.ruleId })

        detector.detect(text, listOf(hostile) + builtIns)
        assertEquals(1, reported.size)
    }

    fun testCarriageReturnsAreNotPartOfTheLine() {
        val text = "Stack trace:\r\n#0 {main}\r\nafter\r\n"

        val block = detect(text).blocks.single()

        assertEquals("Stack trace:\r\n#0 {main}", text.substring(block.range.startOffset, block.range.endOffset))
    }
}
