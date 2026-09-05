package com.pronskiy.agenstorm.scratch

import com.intellij.ide.scratch.ScratchFileTypeFilter
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Steps B1.1/B1.3: the allow-list filter is registered under `scratchLanguageFilter`, so the platform's
 * `ScratchFileTypeFilter.isEnabled` reflects the settings: allow-listed types stay, everything else goes,
 * and switching the feature off restores the full list.
 */
class AllowlistScratchFilterTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testDefaultAllowListKeepsTextMarkdownPhpAndJavaScript() {
        assertEquals(listOf("PLAIN_TEXT", "Markdown", "PHP", "JavaScript"), AgenstormSettings.State().scratchAllowedFileTypes)

        assertTrue(ScratchFileTypeFilter.isEnabled(PlainTextFileType.INSTANCE))
        assertTrue(ScratchFileTypeFilter.isEnabled(fileType("Markdown")))
        assertTrue(ScratchFileTypeFilter.isEnabled(fileType("PHP")))
        assertTrue(ScratchFileTypeFilter.isEnabled(fileType("JavaScript")))

        assertFalse(ScratchFileTypeFilter.isEnabled(fileType("JSON")))
        assertFalse(ScratchFileTypeFilter.isEnabled(fileType("XML")))
        assertFalse(ScratchFileTypeFilter.isEnabled(fileType("YAML")))
    }

    fun testCustomAllowList() {
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State(scratchAllowedFileTypes = mutableListOf("JSON")))

        assertTrue(ScratchFileTypeFilter.isEnabled(fileType("JSON")))
        assertFalse(ScratchFileTypeFilter.isEnabled(fileType("PHP")))
        assertFalse(ScratchFileTypeFilter.isEnabled(PlainTextFileType.INSTANCE))
    }

    fun testFeatureOffProhibitsNothing() {
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State(scratchFilterEnabled = false, scratchAllowedFileTypes = mutableListOf("JSON")))

        val filter = AllowlistScratchFilter()
        assertFalse(filter.isProhibited(fileType("PHP")))
        assertFalse(filter.isProhibited(fileType("XML")))
        assertTrue(ScratchFileTypeFilter.isEnabled(fileType("XML")))
    }

    fun testFilterMatchesByInternalFileTypeName() {
        val filter = AllowlistScratchFilter()
        assertFalse(filter.isProhibited(fileType("PHP")))
        assertTrue(filter.isProhibited(fileType("JSON")))
    }

    private fun fileType(name: String): FileType {
        val type = FileTypeManager.getInstance().findFileTypeByName(name)
        assertNotNull("file type $name is not registered in the test IDE", type)
        return type!!
    }
}
