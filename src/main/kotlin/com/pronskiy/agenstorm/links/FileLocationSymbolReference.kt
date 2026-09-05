package com.pronskiy.agenstorm.links

import com.intellij.codeInsight.highlighting.PsiHighlightedReference
import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement

/**
 * Symbol-API reference for one `path:line[:col]` token inside [host]. Only created for tokens that
 * resolve (see [create]), so unresolved locations get neither a link nor an error: agents routinely
 * mention files they are about to create. [PsiHighlightedReference] makes `HyperlinkAnnotator`
 * render it as a link without any annotator of our own.
 */
class FileLocationSymbolReference private constructor(
    private val host: PsiElement,
    val match: FileLocationMatch,
    private val target: VirtualFile,
) : PsiSymbolReference, PsiHighlightedReference {

    override fun getElement(): PsiElement = host

    override fun getRangeInElement(): TextRange = match.range

    override fun resolveReference(): Collection<Symbol> =
        if (target.isValid) listOf(FileLocationSymbol(target, match.location)) else emptyList()

    companion object {
        /** Requires read access. Null when the location does not resolve to a file. */
        fun create(host: PsiElement, match: FileLocationMatch): FileLocationSymbolReference? {
            val file = FileLocationResolver(host.project).resolve(match.location, host.containingFile) ?: return null
            return FileLocationSymbolReference(host, match, file)
        }
    }
}
