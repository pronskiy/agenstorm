package com.pronskiy.agenstorm.links.markdown

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiExternalReferenceHost
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolReferenceHints
import com.intellij.model.psi.PsiSymbolReferenceProvider
import com.intellij.model.search.SearchRequest
import com.intellij.openapi.project.Project
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.links.FileLocationParser
import com.pronskiy.agenstorm.links.FileLocationSymbolReference
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination

/**
 * Makes `[text](path/File.php:42:7)` navigable: one [FileLocationSymbolReference] per resolvable
 * `path:line[:col]` token in a Markdown link destination. Plain paths and `#anchors` are left to the
 * Markdown plugin, which already handles them.
 */
class MarkdownLocationReferenceProvider : PsiSymbolReferenceProvider {

    override fun getReferences(
        host: PsiExternalReferenceHost,
        hints: PsiSymbolReferenceHints,
    ): Collection<PsiSymbolReference> {
        if (host !is MarkdownLinkDestination) return emptyList()
        if (!AgenstormSettings.getInstance().state.linksEnabled) return emptyList()
        val text = host.text
        if (!LINE_SUFFIX.containsMatchIn(text)) return emptyList()
        val offset = hints.offsetInElement
        return FileLocationParser.parse(text)
            .filter { offset < 0 || it.range.containsOffset(offset) }
            .mapNotNull { FileLocationSymbolReference.create(host, it) }
    }

    override fun getSearchRequests(project: Project, target: Symbol): Collection<SearchRequest> = emptyList()

    private companion object {
        /** Cheap pre-check before running the full parser: a destination without `:<digit>` cannot contain a location. */
        val LINE_SUFFIX = Regex(":\\d")
    }
}
