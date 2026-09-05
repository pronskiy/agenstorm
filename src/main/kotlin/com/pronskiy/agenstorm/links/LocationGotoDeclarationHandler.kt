package com.pronskiy.agenstorm.links

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReferenceService
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Go to Declaration for location links inside composite comments (PHPDoc and similar). Those elements are
 * `ContributedReferenceHost`s, so highlighting finds our references through `PsiReferenceService`, but their
 * own `getReferences()` stays empty and `findReferenceAt` never sees them. This handler fills that gap and
 * deliberately skips hosts whose `getReferences()` already exposes the reference, so plain comments do not
 * get a duplicate target.
 */
class LocationGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        if (sourceElement == null || !AgenstormSettings.getInstance().state.linksEnabled) return null
        var host: PsiElement? = sourceElement
        while (host != null && host !is PsiFile) {
            val hostStart = host.textRange?.startOffset ?: return null
            val offsetInHost = offset - hostStart
            val viaService = PsiReferenceService.getService()
                .getReferences(host, PsiReferenceService.Hints(null, offsetInHost))
                .filterIsInstance<FileLocationPsiReference>()
                .filter { it.rangeInElement.containsOffset(offsetInHost) }
            if (viaService.isNotEmpty()) {
                val exposed = host.references.filterIsInstance<FileLocationPsiReference>().map { it.rangeInElement }.toSet()
                val hidden = viaService.filter { it.rangeInElement !in exposed }
                if (hidden.isEmpty()) return null
                val targets = hidden.mapNotNull { it.resolve() }
                return if (targets.isEmpty()) null else targets.toTypedArray()
            }
            host = host.parent
        }
        return null
    }
}
