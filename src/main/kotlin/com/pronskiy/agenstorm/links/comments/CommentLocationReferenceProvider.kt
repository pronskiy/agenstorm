package com.pronskiy.agenstorm.links.comments

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.ProcessingContext
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.links.FileLocationParser
import com.pronskiy.agenstorm.links.FileLocationPsiReference
import com.pronskiy.agenstorm.links.FileLocationTarget

/**
 * Old-API provider that turns every `path:line[:col]` token in an element's text into a
 * [FileLocationPsiReference]. Registered under `referenceProviderType key="commentsReferenceProvider"`,
 * which the platform applies to every `PsiComment` in every language; the PHP config file reuses it for
 * string literals. Text longer than [MAX_TEXT_LENGTH] is skipped, and the parse runs on a bombed
 * char sequence so a pathological comment cannot stall highlighting.
 */
class CommentLocationReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        if (!AgenstormSettings.getInstance().state.linksEnabled) return PsiReference.EMPTY_ARRAY
        if (element.textLength > MAX_TEXT_LENGTH) return PsiReference.EMPTY_ARRAY
        // The parse depends only on the element text, so it is cached until the PSI changes; resolution is not cached.
        return CachedValuesManager.getCachedValue(element) {
            val text = StringUtil.newBombedCharSequence(element.text, PARSE_TIME_LIMIT_MS)
            val references = FileLocationParser.parse(text)
                .map<_, PsiReference> { FileLocationPsiReference(element, it) }
                .toTypedArray()
            CachedValueProvider.Result.create(references, element)
        }
    }

    override fun acceptsTarget(target: PsiElement): Boolean = target is FileLocationTarget

    companion object {
        /** Comments, heredocs and nowdocs beyond this size are ignored. */
        const val MAX_TEXT_LENGTH = 20_000
        private const val PARSE_TIME_LIMIT_MS = 1_000L
    }
}
