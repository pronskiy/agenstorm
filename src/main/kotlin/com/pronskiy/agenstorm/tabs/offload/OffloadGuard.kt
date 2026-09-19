package com.pronskiy.agenstorm.tabs.offload

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Step P2.4. Something that can keep a project loaded. The platform's only public close,
 * `ProjectManager.closeAndDispose`, asks the user through a modal dialog when a process or a terminal command is
 * still running, and a dialog for a background project is exactly what offloading must never produce — so the
 * guards ask first, and a project any guard objects to is simply skipped (decision 64). The guards are an
 * extension point of Agenstorm's own, `com.pronskiy.agenstorm.offloadGuard`, so that the terminal-aware one can
 * live with the terminal's optional-dependency file and `tabs/` keeps importing only `core/`.
 */
interface OffloadGuard {

    /** A short, user-facing reason why [project] must stay loaded, or null when this guard has none. */
    fun busyReason(project: Project): String?

    companion object {
        val EP_NAME: ExtensionPointName<OffloadGuard> = ExtensionPointName.create("com.pronskiy.agenstorm.offloadGuard")

        /** The first reason any registered guard gives, or null when the project may go. */
        fun busyReason(project: Project): String? = EP_NAME.extensionList.firstNotNullOfOrNull { it.busyReason(project) }
    }
}
