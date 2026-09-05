package com.pronskiy.agenstorm.markdown

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import org.intellij.plugins.markdown.highlighting.MarkdownHighlighterColors
import java.awt.Font

/**
 * Step F2.1: the annotator styles only files with live markup attached and adds exactly what the effective colour
 * scheme does not already render. The bundled scheme renders bold, italic, strike and link text on its own (build
 * 262: `MARKDOWN_LINK_TEXT` falls back to the hyperlink attributes, `MARKDOWN_STRIKE_THROUGH` to the deprecated-code
 * strikeout), so the additions are exercised against a clone of the scheme with those attributes blanked.
 */
class LiveMarkupAnnotatorTest : BasePlatformTestCase() {

    private val text = "**b** *i* ~~s~~ see [the docs](https://x.y) and [ref text][ref]\n\n[ref]: https://z\n"

    override fun tearDown() {
        try {
            LiveMarkupAnnotator.schemeOverride = null
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testTheBundledSchemeAlreadyStylesEverythingSoNothingIsAdded() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val bold = LiveMarkupAnnotator.hasFont(scheme, MarkdownHighlighterColors.BOLD, Font.BOLD)
        val italic = LiveMarkupAnnotator.hasFont(scheme, MarkdownHighlighterColors.ITALIC, Font.ITALIC)
        val strike = LiveMarkupAnnotator.hasEffect(scheme, MarkdownHighlighterColors.STRIKE_THROUGH, EffectType.STRIKEOUT)
        val link = LiveMarkupAnnotator.hasUnderline(scheme, MarkdownHighlighterColors.LINK_TEXT)
        println("live markup annotator: scheme bold=$bold italic=$italic strike=$strike link=$link")
        assertTrue("build 262's bundled scheme styles all four", bold && italic && strike && link)

        myFixture.configureByText("a.md", text)
        assertNotNull(LiveMarkupService.getInstance(project).controllerFor(myFixture.editor))
        assertEmpty(ourInfos())
    }

    fun testABlankSchemeGetsBoldItalicStrikeAndLinkAdded() {
        LiveMarkupAnnotator.schemeOverride = blankScheme()
        myFixture.configureByText("a.md", text)

        val ours = ourInfos()
        assertEquals(4, ours.size)
        assertTrue(ours.all { it.severity == HighlightSeverity.INFORMATION })
        assertTrue(at(ours, 0, "**b**").forcedTextAttributes.fontType and Font.BOLD != 0)
        assertTrue(at(ours, 6, "*i*").forcedTextAttributes.fontType and Font.ITALIC != 0)
        assertEquals(EffectType.STRIKEOUT, at(ours, 10, "~~s~~").forcedTextAttributes.effectType)
        assertEquals(CodeInsightColors.HYPERLINK_ATTRIBUTES, at(ours, text.indexOf("[the docs]"), "[the docs]").forcedTextAttributesKey)
        assertNull("reference links keep their brackets and are not styled as bare links", ours.firstOrNull { it.startOffset == text.indexOf("[ref text]") })
    }

    fun testMissingAttributesNeverRepeatWhatTheSchemeHasAndNeverCarryColours() {
        val bundled = EditorColorsManager.getInstance().globalScheme
        val blank = blankScheme()
        for (styling in LiveMarkupAnnotator.Styling.entries) {
            assertNull("$styling on the bundled scheme", LiveMarkupAnnotator.missingAttributes(styling, bundled))
            val added = LiveMarkupAnnotator.missingAttributes(styling, blank)
            if (styling == LiveMarkupAnnotator.Styling.LINK) {
                assertNull("links use the hyperlink key, not enforced attributes", added)
                continue
            }
            assertNotNull("$styling on a blank scheme", added)
            assertNull(added!!.foregroundColor)
            assertNull(added.backgroundColor)
        }
    }

    fun testFilesWithoutLiveMarkupGetNothingEvenOnABlankScheme() {
        LiveMarkupAnnotator.schemeOverride = blankScheme()
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State(liveMarkupEnabled = false))
        myFixture.configureByText("a.md", text)
        assertNull(LiveMarkupService.getInstance(project).controllerFor(myFixture.editor))
        assertFalse(LiveMarkupService.isActive(myFixture.file.virtualFile))
        assertEmpty(ourInfos())
    }

    fun testDetachDropsTheStylingAndAttachBringsItBack() {
        LiveMarkupAnnotator.schemeOverride = blankScheme()
        myFixture.configureByText("a.md", text)
        assertTrue(LiveMarkupService.isActive(myFixture.file.virtualFile))
        assertEquals(4, ourInfos().size)

        LiveMarkupService.getInstance(project).detach(myFixture.editor)
        assertFalse(LiveMarkupService.isActive(myFixture.file.virtualFile))
        assertEmpty(ourInfos())

        LiveMarkupService.getInstance(project).attach(myFixture.editor)
        assertTrue(LiveMarkupService.isActive(myFixture.file.virtualFile))
        assertEquals(4, ourInfos().size)
    }

    /** The bundled scheme with the four Markdown keys explicitly set to nothing. */
    private fun blankScheme(): EditorColorsScheme {
        val clone = EditorColorsManager.getInstance().globalScheme.clone() as EditorColorsScheme
        for (key in listOf(MarkdownHighlighterColors.BOLD, MarkdownHighlighterColors.ITALIC, MarkdownHighlighterColors.STRIKE_THROUGH, MarkdownHighlighterColors.LINK_TEXT)) {
            clone.setAttributes(key, TextAttributes())
        }
        assertFalse(LiveMarkupAnnotator.hasFont(clone, MarkdownHighlighterColors.BOLD, Font.BOLD))
        assertFalse(LiveMarkupAnnotator.hasUnderline(clone, MarkdownHighlighterColors.LINK_TEXT))
        return clone
    }

    /** Highlighting infos that can only come from LiveMarkupAnnotator: enforced attributes or the hyperlink key. */
    private fun ourInfos(): List<HighlightInfo> = myFixture.doHighlighting().filter {
        it.forcedTextAttributes != null || it.forcedTextAttributesKey == CodeInsightColors.HYPERLINK_ATTRIBUTES
    }

    private fun at(infos: List<HighlightInfo>, start: Int, covered: String): HighlightInfo =
        infos.single { it.startOffset == start && it.endOffset == start + covered.length }
}
