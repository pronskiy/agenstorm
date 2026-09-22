package com.pronskiy.agenstorm.terminal.enhance

import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/** Step I3.1: a user file with a built-in's id replaces it in place, new ids follow, a broken file is an error beside the rules that parsed. */
class RuleCatalogTest : TestCase() {

    private val builtIns = BlockDetector.builtInRules()
    private lateinit var folder: Path

    override fun setUp() {
        super.setUp()
        folder = Files.createTempDirectory("agenstorm-rules")
    }

    override fun tearDown() {
        try {
            folder.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun rule(id: String, summary: String = id) = RuleParser.parse("""{ "id": "$id", "start": "^x$", "summary": "$summary" }""", "$id.json")

    fun testAUserRuleWithABuiltInIdTakesItsPlaceAndNewIdsFollow() {
        val merged = RuleCatalog.merge(builtIns, listOf(rule("zzz-mine"), rule("php-print-r", "mine")))

        assertEquals(listOf("php-var-dump", "php-print-r", "php-var-export", "php-stack-trace", "json-line", "zzz-mine"), merged.map { it.id })
        assertEquals("mine", merged[1].summary)
        assertEquals("php-print-r.json", merged[1].source)
    }

    fun testTwoFilesClaimingOneIdTheLaterNameWins() {
        val merged = RuleCatalog.merge(emptyList(), listOf(rule("a", "first"), rule("a", "second")))

        assertEquals(1, merged.size)
        assertEquals("second", merged.single().summary)
    }

    fun testLoadReadsJsonFilesInNameOrderAndKeepsTheBrokenOnesAsErrors() {
        Files.writeString(folder.resolve("b-second.json"), """{ "id": "second", "start": "^b$" }""")
        Files.writeString(folder.resolve("a-first.json"), """{ "id": "first", "start": "^a$" }""")
        Files.writeString(folder.resolve("broken.json"), """{ "id": "broken", "start": "(" }""")
        Files.writeString(folder.resolve("notes.txt"), "not a rule")

        val catalog = RuleCatalog.load(folder, builtIns)

        assertEquals(builtIns.map { it.id } + listOf("first", "second"), catalog.rules.map { it.id })
        assertEquals("a-first.json", catalog.rules.first { it.id == "first" }.source)
        val error = catalog.errors.single()
        assertEquals("broken.json", error.source)
        assertEquals("start", error.field)
    }

    fun testAMissingFolderIsAnEmptyOne() {
        val catalog = RuleCatalog.load(folder.resolve("nope"), builtIns)

        assertEquals(builtIns.map { it.id }, catalog.rules.map { it.id })
        assertTrue(catalog.errors.isEmpty())
    }

    fun testActiveDropsWhatTheFileOrTheSettingsSwitchedOff() {
        Files.writeString(folder.resolve("off.json"), """{ "id": "off", "start": "^x$", "enabled": false }""")
        val catalog = RuleCatalog.load(folder, builtIns)

        val active = catalog.active(listOf("json-line"))

        assertEquals(listOf("php-var-dump", "php-print-r", "php-var-export", "php-stack-trace"), active.map { it.id })
    }
}
