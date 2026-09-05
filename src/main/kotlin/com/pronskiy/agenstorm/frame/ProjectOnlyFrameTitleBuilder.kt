package com.pronskiy.agenstorm.frame

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.impl.PlatformFrameTitleBuilder
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Replaces the platform's `FrameTitleBuilder` service (`overrides="true"`): while the feature is on, the file
 * part of the window title is empty, so the frame, native macOS project tabs, Mission Control and the Dock
 * show the project title only. `ProjectFrameHelper.updateTitle` skips blank parts, so nothing else changes.
 */
class ProjectOnlyFrameTitleBuilder : PlatformFrameTitleBuilder() {

    private val enabled: Boolean
        get() = AgenstormSettings.getInstance().state.hideFileNameInTitle

    override fun getFileTitle(project: Project, file: VirtualFile): String =
        if (enabled) "" else super.getFileTitle(project, file)

    override suspend fun getFileTitleAsync(project: Project, file: VirtualFile): String =
        if (enabled) "" else super.getFileTitleAsync(project, file)
}
