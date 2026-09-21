package com.pronskiy.agenstorm.markdown

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination
import org.jetbrains.annotations.TestOnly

/**
 * Step Q2.4. Opens a link clicked on a rendered table (Epic Q) the way Go to Declaration opens the same link in
 * the text: the destination element inside the clicked cell is resolved by [LinkTextGotoDeclarationHandler.targetsOf]
 * — a `path:line:col`, a heading anchor, a file — and the first target navigates; a URL or a `mailto:` goes straight
 * to the browser, whether it came from an inline link or from an autolink, which has no destination element at all.
 */
object LinkDestinations {

    /** EDT. [within] is the clicked cell's content range, [link] the destination exactly as the run carries it. */
    fun open(project: Project, editor: Editor, within: TextRange, link: String) {
        if (BrowserUtil.isAbsoluteURL(link) || link.startsWith(MAILTO)) {
            browse(link)
            return
        }
        val target = ReadAction.compute<PsiElement?, RuntimeException> {
            val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@compute null
            val element = file.findElementAt(within.startOffset) ?: return@compute null
            val cell = generateSequence(element) { it.parent }.firstOrNull { it.textRange.contains(within) } ?: file
            val destination = PsiTreeUtil.findChildrenOfType(cell, MarkdownLinkDestination::class.java)
                .firstOrNull { within.contains(it.textRange) && it.text.trim() == link }
            destination?.let { LinkTextGotoDeclarationHandler.targetsOf(it).firstOrNull() }
        }
        val navigatable = target as? Navigatable ?: return
        if (navigatable.canNavigate()) navigatable.navigate(true)
    }

    private fun browse(url: String) {
        val override = browserOverride
        if (override != null) override(url) else BrowserUtil.browse(url)
    }

    private const val MAILTO = "mailto:"

    /** Tests observe the URL instead of opening a browser. */
    @TestOnly
    @Volatile
    var browserOverride: ((String) -> Unit)? = null
}
