package com.pronskiy.agenstorm.links.markdown

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.pronskiy.agenstorm.links.FileLocationSymbolReference
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination

/**
 * The Markdown plugin's own file references cannot resolve `src/Foo.php:42:7`, so its
 * "Cannot resolve file" inspection would flag every location link. Suppress that one inspection on
 * destinations where our provider found a resolvable location; genuinely broken links keep the warning.
 */
class LocationLinkInspectionSuppressor : InspectionSuppressor, DumbAware {

    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId != UNRESOLVED_FILE_REFERENCE) return false
        val destination = element as? MarkdownLinkDestination
            ?: PsiTreeUtil.getParentOfType(element, MarkdownLinkDestination::class.java, false)
            ?: return false
        return PsiSymbolReferenceService.getService()
            .getReferences(destination, FileLocationSymbolReference::class.java)
            .isNotEmpty()
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> = SuppressQuickFix.EMPTY_ARRAY

    private companion object {
        /** `shortName` of `org.intellij.plugins.markdown.lang.references.paths.MarkdownUnresolvedFileReferenceInspection`. */
        const val UNRESOLVED_FILE_REFERENCE = "MarkdownUnresolvedFileReference"
    }
}
