package com.pronskiy.agenstorm.terminal.enhance

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import java.nio.file.Files

/** Steps I3.1 and I3.2: the folder under the IDE config, read on first use, a built-in copied out of the plugin, a broken file said once. */
class RuleRepositoryTest : BasePlatformTestCase() {

    private val repository get() = RuleRepository.getInstance()

    override fun setUp() {
        super.setUp()
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        clearFolder()
        repository.forgetForTest()
    }

    override fun tearDown() {
        try {
            clearFolder()
            repository.forgetForTest()
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private fun clearFolder() {
        val folder = repository.folder
        if (Files.isDirectory(folder)) Files.list(folder).use { s -> s.forEach { Files.deleteIfExists(it) } }
    }

    fun testTheFolderLivesUnderTheConfigDirectoryAndIsCreatedOnFirstUse() {
        assertTrue(repository.folder.endsWith("agenstorm/terminal-rules"))

        val rules = repository.rules

        assertTrue(Files.isDirectory(repository.folder))
        assertEquals(BlockDetector.BUILT_IN_RULE_FILES.size, rules.size)
        assertTrue(rules.all { it.source == EnhancerRule.BUILT_IN_SOURCE })
    }

    fun testAFileInTheFolderIsAUserRuleAfterAReload() {
        repository.ensureFolder()
        Files.writeString(repository.folder.resolve("laravel-dd.json"), """{ "id": "laravel-dd", "start": "^\\^ array:\\d+ \\[$", "end": "^]$", "summary": "dd …" }""")

        val catalog = repository.reload()

        val mine = catalog.rules.single { it.id == "laravel-dd" }
        assertEquals("laravel-dd.json", mine.source)
        assertTrue(repository.activeRules().any { it.id == "laravel-dd" })
    }

    fun testASwitchedOffRuleIsNotActive() {
        AgenstormSettings.getInstance().state.terminalEnhancerDisabledRules = mutableListOf("json-line")

        assertTrue(repository.rules.any { it.id == "json-line" })
        assertFalse(repository.activeRules().any { it.id == "json-line" })
    }

    fun testCopyingABuiltInWritesItsFileOnceAndTheCopyOverridesIt() {
        val path = repository.copyBuiltIn("php-var-dump")!!

        assertEquals("php-var-dump.json", path.fileName.toString())
        assertTrue(Files.readString(path).contains("\"id\": \"php-var-dump\""))
        assertEquals("php-var-dump.json", repository.rules.single { it.id == "php-var-dump" }.source)
        assertNull("the copy is never overwritten", repository.copyBuiltIn("php-var-dump"))
        assertNull(repository.copyBuiltIn("no-such-rule"))
    }

    fun testABrokenFileIsAnErrorTheOtherRulesStillLoadAndItIsReportedOnce() {
        repository.ensureFolder()
        Files.writeString(repository.folder.resolve("broken.json"), """{ "id": "broken", "start": "[" }""")
        Files.writeString(repository.folder.resolve("fine.json"), """{ "id": "fine", "start": "^ok$" }""")

        val first = repository.reload()

        assertEquals("broken.json", first.errors.single().source)
        assertTrue(first.rules.any { it.id == "fine" })
        assertTrue("reported, so not unseen any more", RuleFileNotice.unseen(first.errors).isEmpty())

        Files.writeString(repository.folder.resolve("broken.json"), """{ "id": "broken", "start": "[", "end": 3 }""")
        val second = repository.reload()
        assertEquals("the same mistake is not news", 0, RuleFileNotice.unseen(second.errors).size)
    }
}
