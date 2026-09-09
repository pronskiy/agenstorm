package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Key

/**
 * Step F4.1. Makes the markers we replace look like text rather than like folded code.
 *
 * Three of the twelve [MarkupKind]s fold to a *visible* placeholder — a bullet's • and the two checkbox boxes;
 * everything else folds to zero width and paints nothing. A collapsed placeholder is painted with the colour
 * scheme's `FOLDED_TEXT_ATTRIBUTES`, styling meant for the `{...}` that stands in for hidden code: Catppuccin
 * Mocha, for one, gives it a background (`313244`) and `EFFECT_TYPE=2` (`BOXED`), so every bullet came out in a
 * little box. That is per editor, never per region — `EditorPainter.getFoldRegionAttributes` merges the
 * selection, `FoldingModelEx.getPlaceholderAttributes()` and the defaults and offers no hook of its own, while
 * `FoldingModelImpl.updateTextAttributes()` is simply `editor.colorsScheme.getAttributes(FOLDED_TEXT_ATTRIBUTES)`.
 *
 * So the value is overridden for this editor alone. `EditorEx.getColorsScheme()` always hands back the editor's
 * own scheme delegate, which keeps a per-editor attribute map that `setAttributes` writes into and `getAttributes`
 * reads first; `reinitSettings()` then has the folding model re-read it. Both are public API and the override
 * dies with the editor.
 *
 * The cost, accepted on 2026-09-09: a genuinely folded heading or code fence in the same editor shows its `...`
 * as plain text too, without the scheme's background and border. Only Markdown editors with the feature on are
 * touched, and [uninstall] puts the scheme's own value back.
 */
object FoldPlaceholderStyle {

    private val LOG = logger<FoldPlaceholderStyle>()

    /** What the editor's folded-text attributes were before we overrode them; also our "installed" marker. */
    private class Original(val attributes: TextAttributes?)

    private val ORIGINAL = Key.create<Original>("agenstorm.markdown.foldedTextOriginal")

    /**
     * No colour, no effect, no font of its own, so the placeholder is painted with the editor's defaults. A fresh
     * instance per editor: [TextAttributes] is mutable and nothing here should share one.
     */
    private fun plain() = TextAttributes()

    /**
     * Idempotent, and a no-op for a scheme that already paints folded text as plain text — `reinitSettings` is a
     * full editor re-init and is not worth spending when there is nothing to change. Fails soft: a marker in a
     * box is a blemish, not a reason to throw.
     */
    fun install(editor: EditorEx) {
        if (editor.getUserData(ORIGINAL) != null) return
        try {
            val scheme = editor.colorsScheme
            val current = scheme.getAttributes(EditorColors.FOLDED_TEXT_ATTRIBUTES)
            if (isPlain(current)) return
            editor.putUserData(ORIGINAL, Original(current))
            scheme.setAttributes(EditorColors.FOLDED_TEXT_ATTRIBUTES, plain())
            editor.reinitSettings()
        } catch (e: Throwable) {
            editor.putUserData(ORIGINAL, null)
            LOG.warn("Cannot neutralise the folded-text attributes; markers keep the scheme's folded-code styling", e)
        }
    }

    /** Nothing that would show around a placeholder: no block behind it, no border, no colour of its own. */
    private fun isPlain(attributes: TextAttributes?): Boolean =
        attributes == null ||
            (attributes.backgroundColor == null && attributes.effectColor == null && attributes.foregroundColor == null)

    /** Puts back what the scheme said before [install], and only for an editor we actually changed. */
    fun uninstall(editor: EditorEx) {
        val original = editor.getUserData(ORIGINAL) ?: return
        editor.putUserData(ORIGINAL, null)
        try {
            editor.colorsScheme.setAttributes(EditorColors.FOLDED_TEXT_ATTRIBUTES, original.attributes)
            editor.reinitSettings()
        } catch (e: Throwable) {
            LOG.warn("Cannot restore the editor's folded-text attributes", e)
        }
    }

    /** Whether this editor currently carries our override. */
    fun isInstalled(editor: EditorEx): Boolean = editor.getUserData(ORIGINAL) != null
}
