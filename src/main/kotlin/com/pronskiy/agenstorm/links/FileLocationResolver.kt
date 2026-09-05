package com.pronskiy.agenstorm.links

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Turns a written [FileLocation] into a [VirtualFile] and an offset.
 *
 * Candidate order: absolute path (when it starts with `/`), the directory containing [PsiFile] context,
 * the project base directory, every content root, and finally a unique file name anywhere in the
 * project. Only the last step touches the index; it is skipped in dumb mode.
 */
class FileLocationResolver(private val project: Project) {

    /** Requires read access. Returns null when nothing matches; never throws (except cancellation). */
    fun resolve(location: FileLocation, context: PsiFile?): VirtualFile? {
        val path = location.path
        directCandidates(path, context).firstOrNull { it != null && !it.isDirectory }?.let { return it }
        return resolveByBasename(path)
    }

    /** Offset of [location] inside [file], clamped to the document; the line start when there is no column. */
    fun toOffset(file: VirtualFile, location: FileLocation): Int {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return 0
        if (document.lineCount == 0) return 0
        val line = (location.line - 1).coerceIn(0, document.lineCount - 1)
        val lineStart = document.getLineStartOffset(line)
        val column = location.column ?: return lineStart
        return (lineStart + column - 1).coerceIn(lineStart, document.getLineEndOffset(line))
    }

    private fun directCandidates(path: String, context: PsiFile?): Sequence<VirtualFile?> = sequence {
        if (path.startsWith("/")) yield(LocalFileSystem.getInstance().findFileByPath(path))
        context?.originalFile?.virtualFile?.parent?.let { yield(it.findFileByRelativePath(path)) }
        project.guessProjectDir()?.let { yield(it.findFileByRelativePath(path)) }
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            yield(root.findFileByRelativePath(path))
        }
    }

    private fun resolveByBasename(path: String): VirtualFile? {
        if (DumbService.isDumb(project)) return null
        val name = path.substringAfterLast('/')
        if (name.isEmpty()) return null
        val candidates = try {
            FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project)).filter { !it.isDirectory }
        } catch (_: IndexNotReadyException) {
            return null
        }
        val suffix = "/" + path.trimStart('.', '/')
        return candidates.singleOrNull { it.path.endsWith(suffix) } ?: candidates.singleOrNull()
    }
}
