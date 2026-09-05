package com.pronskiy.agenstorm.links

import com.intellij.codeInsight.highlighting.HighlightedReference
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.impl.FakePsiElement
import javax.swing.Icon

/**
 * Old-API counterpart of [FileLocationSymbolReference] for hosts that only support `PsiReference`
 * (comments through `commentsReferenceProvider`, PHP string literals). Soft, so an unresolved location is
 * never an error; highlighted by `HyperlinkAnnotator` only while it resolves, so a file created later
 * becomes a link on the next highlighting pass.
 */
class FileLocationPsiReference(host: PsiElement, val match: FileLocationMatch) :
    PsiReferenceBase<PsiElement>(host, match.range, true), HighlightedReference {

    override fun resolve(): PsiElement? {
        val file = FileLocationResolver(element.project).resolve(match.location, element.containingFile) ?: return null
        return FileLocationTarget(element.manager, file, match.location)
    }

    override fun isHighlightedWhenSoft(): Boolean = resolve() != null

    override fun getVariants(): Array<Any> = emptyArray()
}

/** Navigatable stand-in for "this file at this line and column"; equal for the same file and location. */
class FileLocationTarget(
    private val psiManager: PsiManager,
    val file: VirtualFile,
    val location: FileLocation,
) : FakePsiElement() {

    override fun getParent(): PsiElement? = psiManager.findFile(file)

    override fun getContainingFile(): PsiFile? = psiManager.findFile(file)

    override fun getProject(): Project = psiManager.project

    override fun getManager(): PsiManager = psiManager

    override fun getName(): String = "${file.name}:${location.line}"

    override fun isValid(): Boolean = file.isValid

    override fun getNavigationElement(): PsiElement = this

    override fun canNavigate(): Boolean = file.isValid

    override fun canNavigateToSource(): Boolean = file.isValid

    override fun navigate(requestFocus: Boolean) {
        if (!file.isValid) return
        val offset = FileLocationResolver(project).toOffset(file, location)
        OpenFileDescriptor(project, file, offset).navigate(requestFocus)
    }

    override fun getPresentableText(): String = name

    override fun getLocationString(): String? = file.parent?.presentableUrl

    override fun getIcon(unused: Boolean): Icon? = file.fileType.icon

    override fun equals(other: Any?): Boolean =
        other is FileLocationTarget && other.file == file && other.location == location

    override fun hashCode(): Int = 31 * file.hashCode() + location.hashCode()
}
