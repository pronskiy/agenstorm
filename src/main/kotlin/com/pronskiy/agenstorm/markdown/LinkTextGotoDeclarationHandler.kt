package com.pronskiy.agenstorm.markdown

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.ide.BrowserUtil
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.paths.WebReference
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.pronskiy.agenstorm.core.AgenstormSettings
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownInlineLink
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkText
import javax.swing.Icon

/**
 * Step F2.3. With the link tail folded away, the visible text of `[text](destination)` is what the user can Ctrl/Cmd+
 * click, so Go to Declaration from anywhere inside inline-link text answers with the hidden destination's targets:
 * a URL opens in the browser, a `path:line:col` (Epic A's Symbol-API reference) opens the file at that spot, and the
 * Markdown plugin's own file and heading references do what they always did. Plugging into the platform's handler
 * gives Ctrl+click, Ctrl+B and the Ctrl-hover underline in one go, without editor mouse code or internal API.
 *
 * `MarkdownLinkText` accepts no reference providers of either API, which is why this is a handler, not a reference.
 * Heading anchors are matched here through `MarkdownHeader.anchorText`: the plugin resolves them to header symbols
 * that only the platform's internal navigation service can open.
 */
class LinkTextGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        if (sourceElement == null || !AgenstormSettings.getInstance().state.liveMarkupEnabled) return null
        val linkText = PsiTreeUtil.getParentOfType(sourceElement, MarkdownLinkText::class.java, false) ?: return null
        val link = linkText.parent as? MarkdownInlineLink ?: return null
        val destination = PsiTreeUtil.getChildOfType(link, MarkdownLinkDestination::class.java) ?: return null
        val targets = targetsOf(destination)
        return if (targets.isEmpty()) null else targets.toTypedArray()
    }

    companion object {
        /**
         * Read access required. URLs first (a `WebReference` target browses on navigation), then `path#anchor` (the
         * heading, else the file), then Symbol-API references whose symbols are [Navigatable], then old-API references —
         * only those reaching the end of the destination, so a path's directory segments do not turn one link into a
         * chooser popup.
         */
        fun targetsOf(destination: PsiElement): List<PsiElement> {
            val text = destination.text.trim()
            if (BrowserUtil.isAbsoluteURL(text)) {
                return listOfNotNull(WebReference(destination, TextRange(0, destination.textLength), text).resolve())
            }
            val hash = text.indexOf('#')
            if (hash >= 0) return listOfNotNull(anchorTarget(destination, hash, text.substring(hash + 1)))
            val out = LinkedHashSet<PsiElement>()
            for (reference in PsiSymbolReferenceService.getService().getReferences(destination)) {
                for (symbol in reference.resolveReference()) {
                    val navigatable = symbol as? Navigatable ?: continue
                    if (navigatable.canNavigate()) out += SymbolTarget(destination, navigatable)
                }
            }
            val references = destination.references
            val toTheEnd = references.filter { it.rangeInElement.endOffset >= destination.textLength }.ifEmpty { references.toList() }
            for (reference in toTheEnd) {
                val resolved = if (reference is PsiPolyVariantReference) reference.multiResolve(false).mapNotNull { it.element } else listOfNotNull(reference.resolve())
                out += resolved
            }
            return out.toList()
        }

        /** The heading with [anchor] in the file before `#` (this file when there is none); the file itself when no heading matches. */
        private fun anchorTarget(destination: PsiElement, hash: Int, anchor: String): PsiElement? {
            val file: PsiFile? = if (hash == 0) {
                destination.containingFile
            } else {
                destination.references.filterNotNull().filter { it.rangeInElement.endOffset == hash }.firstNotNullOfOrNull { it.resolve() as? PsiFile }
            }
            if (file == null) return null
            val header = PsiTreeUtil.findChildrenOfType(file, MarkdownHeader::class.java).firstOrNull { it.anchorText.equals(anchor, ignoreCase = true) }
            return header ?: file.takeIf { hash > 0 }
        }
    }
}

/** A navigable symbol as a Go to Declaration target; navigation is delegated, everything else points at the destination. */
private class SymbolTarget(private val destination: PsiElement, private val symbol: Navigatable) : FakePsiElement() {

    override fun getParent(): PsiElement = destination

    override fun getContainingFile(): PsiFile? = destination.containingFile

    override fun getProject(): Project = destination.project

    override fun getManager(): PsiManager = destination.manager

    override fun isValid(): Boolean = destination.isValid

    override fun getName(): String = destination.text

    override fun getNavigationElement(): PsiElement = this

    override fun canNavigate(): Boolean = symbol.canNavigate()

    override fun canNavigateToSource(): Boolean = symbol.canNavigateToSource()

    override fun navigate(requestFocus: Boolean) = symbol.navigate(requestFocus)

    override fun getPresentableText(): String = name

    override fun getIcon(unused: Boolean): Icon? = null

    override fun equals(other: Any?): Boolean = other is SymbolTarget && other.symbol == symbol

    override fun hashCode(): Int = symbol.hashCode()
}
