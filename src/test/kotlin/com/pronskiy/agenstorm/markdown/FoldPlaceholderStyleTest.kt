package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Color
import java.awt.Font

/**
 * Step F4.1: a bullet or checkbox is painted as text, not as folded code. Catppuccin Mocha's folded-text
 * attributes — a background plus `BOXED` — are the case that put every bullet in a little box.
 */
class FoldPlaceholderStyleTest : BasePlatformTestCase() {

    private val key = EditorColors.FOLDED_TEXT_ATTRIBUTES

    private fun boxedLikeCatppuccin() = TextAttributes(Color(0x6c7086), Color(0x313244), Color(0x6c7086), EffectType.BOXED, Font.PLAIN)

    /** A Markdown editor with live markup on, so the controller has already installed the override. */
    private fun markdownEditor(): EditorEx {
        myFixture.configureByText("a.md", "- one\n")
        return myFixture.editor as EditorEx
    }

    fun testTheEditorPaintsFoldedTextWithNothingOfItsOwn() {
        val editor = markdownEditor()
        editor.colorsScheme.setAttributes(key, boxedLikeCatppuccin())
        FoldPlaceholderStyle.uninstall(editor)

        FoldPlaceholderStyle.install(editor)

        val attributes = editor.colorsScheme.getAttributes(key)
        assertNotNull(attributes)
        assertNull("a bullet must not sit on a block", attributes!!.backgroundColor)
        assertNull("nor in a box: a null effect colour is what stops the effect being painted", attributes.effectColor)
        assertNull(attributes.foregroundColor)
    }

    fun testInstallIsIdempotent() {
        val editor = markdownEditor()
        FoldPlaceholderStyle.uninstall(editor)
        editor.colorsScheme.setAttributes(key, boxedLikeCatppuccin())

        FoldPlaceholderStyle.install(editor)
        FoldPlaceholderStyle.install(editor)
        FoldPlaceholderStyle.uninstall(editor)

        assertEquals(
            "a second install must not record the neutral value as the original",
            boxedLikeCatppuccin(),
            editor.colorsScheme.getAttributes(key),
        )
    }

    fun testUninstallRestoresTheSchemesOwnValue() {
        val editor = markdownEditor()
        FoldPlaceholderStyle.uninstall(editor)
        val boxed = boxedLikeCatppuccin()
        editor.colorsScheme.setAttributes(key, boxed)

        FoldPlaceholderStyle.install(editor)
        assertTrue(FoldPlaceholderStyle.isInstalled(editor))
        FoldPlaceholderStyle.uninstall(editor)

        assertFalse(FoldPlaceholderStyle.isInstalled(editor))
        assertEquals(boxed, editor.colorsScheme.getAttributes(key))
    }

    fun testUninstallingAnEditorWeNeverTouchedChangesNothing() {
        val editor = markdownEditor()
        FoldPlaceholderStyle.uninstall(editor)
        val boxed = boxedLikeCatppuccin()
        editor.colorsScheme.setAttributes(key, boxed)

        FoldPlaceholderStyle.uninstall(editor)

        assertEquals(boxed, editor.colorsScheme.getAttributes(key))
    }

    fun testEveryOtherAttributeIsLeftAlone() {
        val editor = markdownEditor()
        val before = listOf(EditorColors.SEARCH_RESULT_ATTRIBUTES, EditorColors.IDENTIFIER_UNDER_CARET_ATTRIBUTES)
            .associateWith { editor.colorsScheme.getAttributes(it) }

        FoldPlaceholderStyle.uninstall(editor)
        FoldPlaceholderStyle.install(editor)

        for ((otherKey, attributes) in before) {
            assertEquals("key $otherKey must be untouched", attributes, editor.colorsScheme.getAttributes(otherKey))
        }
    }

    fun testASchemeThatAlreadyPaintsFoldedTextPlainlyIsLeftAlone() {
        val editor = markdownEditor()
        FoldPlaceholderStyle.uninstall(editor)
        editor.colorsScheme.setAttributes(key, TextAttributes())

        FoldPlaceholderStyle.install(editor)

        assertFalse("nothing to change means no editor re-init", FoldPlaceholderStyle.isInstalled(editor))
    }

    /** The contract, whichever way it is reached: an editor live markup owns never paints a marker as folded code. */
    fun testAMarkdownEditorNeverPaintsMarkersAsFoldedCode() {
        val editor = markdownEditor()
        assertNotNull(LiveMarkupService.getInstance(project).controllerFor(editor))

        val attributes = editor.colorsScheme.getAttributes(key)
        if (attributes != null) {
            assertNull(attributes.backgroundColor)
            assertNull(attributes.effectColor)
            assertNull(attributes.foregroundColor)
        }
    }
}
