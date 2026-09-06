package com.pronskiy.agenstorm.links

import com.intellij.model.Pointer
import com.intellij.model.Symbol
import com.intellij.navigation.NavigatableSymbol
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable

/**
 * Symbol-API target of a resolved `path:line[:col]` token: a [file] plus the written [location].
 * Equality is by file and location, so the platform can merge references to the same spot.
 *
 * Also a plain [Navigatable], so code outside the Symbol API (the Markdown live-markup link handler) can open it
 * without the platform's internal navigation services: the file opens in the project that owns it.
 */
data class FileLocationSymbol(val file: VirtualFile, val location: FileLocation) : NavigatableSymbol, Navigatable {

    override fun createPointer(): Pointer<out Symbol> = Pointer { if (file.isValid) this else null }

    override fun getNavigationTargets(project: Project): Collection<NavigationTarget> =
        if (file.isValid) listOf(FileLocationNavigationTarget(project, file, location)) else emptyList()

    override fun canNavigate(): Boolean = file.isValid

    override fun canNavigateToSource(): Boolean = file.isValid

    override fun navigate(requestFocus: Boolean) {
        if (!file.isValid) return
        val project = ProjectLocator.getInstance().guessProjectForFile(file)
            ?: ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
            ?: return
        OpenFileDescriptor(project, file, FileLocationResolver(project).toOffset(file, location)).navigate(requestFocus)
    }
}

/** Navigation target that opens [file] at [location]; the offset is computed from the current document. */
class FileLocationNavigationTarget(
    private val project: Project,
    val file: VirtualFile,
    val location: FileLocation,
) : NavigationTarget {

    /** Clamped document offset of the written line and column (see [FileLocationResolver.toOffset]). */
    val offset: Int
        get() = FileLocationResolver(project).toOffset(file, location)

    override fun createPointer(): Pointer<out NavigationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation {
        val builder = TargetPresentation.builder("${file.name}:${location.line}")
        file.fileType.icon?.let { builder.icon(it) }
        file.parent?.presentableUrl?.let { builder.containerText(it) }
        return builder.presentation()
    }

    override fun navigationRequest(): NavigationRequest? =
        if (file.isValid) NavigationRequest.sourceNavigationRequest(project, file, offset) else null

    override fun equals(other: Any?): Boolean =
        other is FileLocationNavigationTarget && other.file == file && other.location == location

    override fun hashCode(): Int = 31 * file.hashCode() + location.hashCode()
}
