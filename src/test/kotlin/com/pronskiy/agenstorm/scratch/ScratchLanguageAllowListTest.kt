package com.pronskiy.agenstorm.scratch

import com.intellij.lang.Language
import com.intellij.lang.LanguageUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Step B2.1: which languages the New Scratch File popup offers. Entries are matched leniently — by language id,
 * by display name, or by the name of the language's file type — so a list written against 1.0's file-type names
 * keeps working, and the filter is a *language* filter, which the file-type-level one it replaces could not be.
 *
 * Real registered languages, not fakes: a `Language` is registered by class as it is constructed, so a test
 * cannot make its own, and the ones the IDE ships are what the popup will actually hold anyway.
 */
class ScratchLanguageAllowListTest : BasePlatformTestCase() {

    private val plainText get() = language("TEXT")
    private val php get() = language("PHP")
    private val markdown get() = language("Markdown")
    private val javaScript get() = language("JavaScript")
    private val ecmaScript6 get() = language("ECMAScript 6")
    private val actionScript get() = language("ECMA Script Level 4")
    private val json get() = language("JSON")

    private fun language(id: String): Language =
        Language.findLanguageByID(id) ?: error("this IDE has no '$id' language, the test fixture changed")

    private val candidates get() = listOf(plainText, php, markdown, javaScript, json)

    fun testKeepsOnlyTheAllowedLanguages() {
        val shown = ScratchLanguageAllowList.filter(candidates, listOf("PHP", "JSON"))

        assertEquals(listOf(php, json), shown)
    }

    fun testShowsThemInTheOrderTheListNamesThem() {
        val shown = ScratchLanguageAllowList.filter(candidates, listOf("JSON", "PHP"))

        assertEquals(listOf(json, php), shown)
    }

    fun testMatchesByIdByDisplayNameAndByFileTypeName() {
        // Plain text is all three at once: id TEXT, display name "Plain text", file type PLAIN_TEXT.
        assertEquals(listOf(plainText), ScratchLanguageAllowList.filter(candidates, listOf("TEXT")))
        assertEquals(listOf(plainText), ScratchLanguageAllowList.filter(candidates, listOf("Plain text")))
        assertEquals(listOf(plainText), ScratchLanguageAllowList.filter(candidates, listOf("PLAIN_TEXT")))
    }

    /** What a settings file written by 1.0 or 1.1 holds; it has to keep working. */
    fun testTheDefaultsOfTheFileTypeEraStillResolve() {
        val legacy = listOf("PLAIN_TEXT", "Markdown", "PHP", "JavaScript")

        assertEquals(listOf(plainText, markdown, php, javaScript), ScratchLanguageAllowList.filter(candidates, legacy))
    }

    fun testMatchingIsCaseInsensitiveAndIgnoresSurroundingSpace() {
        val shown = ScratchLanguageAllowList.filter(candidates, listOf("  php  ", "json", ""))

        assertEquals(listOf(php, json), shown)
    }

    /**
     * The whole point of filtering languages rather than file types. All three of these share the JavaScript
     * *file type*, which is why the internal `scratchLanguageFilter` could not keep the dialects out and
     * decision 14 had to document them as a limitation.
     */
    fun testDialectsNoLongerRideAlongWithTheirFileType() {
        val withDialects = listOf(javaScript, ecmaScript6, actionScript)
        assertEquals(
            listOf("JavaScript", "JavaScript", "JavaScript"),
            withDialects.map { LanguageUtil.getLanguageFileType(it)?.name },
        )

        val shown = ScratchLanguageAllowList.filter(withDialects, listOf("JavaScript"))

        assertEquals(listOf(javaScript), shown)
    }

    /** …and naming a dialect still offers exactly that dialect. */
    fun testADialectCanBeAllowedOnItsOwn() {
        val withDialects = listOf(javaScript, ecmaScript6, actionScript)

        assertEquals(listOf(ecmaScript6), ScratchLanguageAllowList.filter(withDialects, listOf("ECMAScript 6")))
        assertEquals(listOf(actionScript), ScratchLanguageAllowList.filter(withDialects, listOf("ActionScript")))
    }

    fun testUnknownEntriesAreIgnoredAndNothingIsListedTwice() {
        val shown = ScratchLanguageAllowList.filter(candidates, listOf("Nonexistent", "PHP", "php"))

        assertEquals(listOf(php), shown)
    }

    fun testAnEmptyListHidesEverythingSoTheFeatureCannotSilentlyDoNothing() {
        assertEquals(emptyList<Language>(), ScratchLanguageAllowList.filter(candidates, emptyList()))
    }
}
