package com.pronskiy.agenstorm.markdown

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiUtilCore
import org.intellij.plugins.markdown.highlighting.MarkdownHighlighterColors
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import java.awt.Font

/**
 * Step F2.1. While live markup is on for a file, the text whose markers are hidden must look styled, or `bold` is
 * indistinguishable from bold. The colour scheme usually does most of it already (the Markdown plugin's own
 * annotator applies its keys), so this annotator only fills the gaps it finds in the effective scheme: a bold font
 * for `STRONG`, italic for `EMPH`, a strikeout for `STRIKETHROUGH`, and hyperlink attributes for the visible text of
 * an inline link. Layers merge font styles and keep the colours underneath, so nothing the scheme paints is lost.
 *
 * Files without an attached [LiveMarkupController] get nothing (see [LiveMarkupService.isActive]).
 */
class LiveMarkupAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val styling = when (PsiUtilCore.getElementType(element)) {
            MarkdownElementTypes.STRONG -> Styling.BOLD
            MarkdownElementTypes.EMPH -> Styling.ITALIC
            MarkdownElementTypes.STRIKETHROUGH -> Styling.STRIKE
            MarkdownElementTypes.LINK_TEXT -> if (PsiUtilCore.getElementType(element.parent) == MarkdownElementTypes.INLINE_LINK) Styling.LINK else return
            else -> return
        }
        if (!LiveMarkupService.isActive(element.containingFile.viewProvider.virtualFile)) return
        val scheme = schemeOverride ?: EditorColorsManager.getInstance().globalScheme
        when (styling) {
            Styling.LINK -> if (!hasUnderline(scheme, MarkdownHighlighterColors.LINK_TEXT)) {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(element).textAttributes(CodeInsightColors.HYPERLINK_ATTRIBUTES).create()
            }
            else -> missingAttributes(styling, scheme)?.let {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(element).enforcedTextAttributes(it).create()
            }
        }
    }

    enum class Styling { BOLD, ITALIC, STRIKE, LINK }

    companion object {
        /** Tests only: a scheme to judge instead of the global one, so the "scheme lacks it" branches can be exercised. */
        @Volatile
        internal var schemeOverride: EditorColorsScheme? = null

        /** The attributes to layer on top for [styling], or null when the scheme already renders it. */
        fun missingAttributes(styling: Styling, scheme: EditorColorsScheme): TextAttributes? = when (styling) {
            Styling.BOLD -> if (hasFont(scheme, MarkdownHighlighterColors.BOLD, Font.BOLD)) null else fontOnly(Font.BOLD)
            Styling.ITALIC -> if (hasFont(scheme, MarkdownHighlighterColors.ITALIC, Font.ITALIC)) null else fontOnly(Font.ITALIC)
            Styling.STRIKE -> if (hasEffect(scheme, MarkdownHighlighterColors.STRIKE_THROUGH, EffectType.STRIKEOUT)) null else TextAttributes(null, null, scheme.defaultForeground, EffectType.STRIKEOUT, Font.PLAIN)
            Styling.LINK -> null
        }

        fun hasFont(scheme: EditorColorsScheme, key: TextAttributesKey, font: Int): Boolean =
            (scheme.getAttributes(key)?.fontType ?: Font.PLAIN) and font != 0

        fun hasEffect(scheme: EditorColorsScheme, key: TextAttributesKey, effect: EffectType): Boolean =
            scheme.getAttributes(key)?.let { it.effectType == effect && it.effectColor != null } == true

        fun hasUnderline(scheme: EditorColorsScheme, key: TextAttributesKey): Boolean =
            hasEffect(scheme, key, EffectType.LINE_UNDERSCORE) || hasEffect(scheme, key, EffectType.BOLD_LINE_UNDERSCORE)

        private fun fontOnly(font: Int) = TextAttributes(null, null, null, null, font)
    }
}
