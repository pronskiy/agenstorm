package com.pronskiy.agenstorm.core.busy

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Step P2.4, moved to `core/` by T1.1. Something that keeps a project open. The platform's only public close,
 * `ProjectManager.closeAndDispose`, asks the user through a modal dialog when a process or a terminal command is
 * still running, and a dialog for a project the user is not looking at is exactly what offloading (Epic P) and the
 * worktree switch (Epic T) must never produce — so both ask the guards first, and a project any guard objects to is
 * left open (decisions 64 and 74). The guards are an extension point of Agenstorm's own, still named
 * `com.pronskiy.agenstorm.offloadGuard`, so that the terminal-aware one can live with the terminal's
 * optional-dependency file and every feature keeps importing only `core/`.
 */
interface ProjectBusyGuard {

    /** A short, user-facing reason why [project] must stay open, or null when this guard has none. */
    fun busyReason(project: Project): String?

    companion object {
        val EP_NAME: ExtensionPointName<ProjectBusyGuard> = ExtensionPointName.create("com.pronskiy.agenstorm.offloadGuard")

        /** The first reason any registered guard gives, or null when the project may be closed. */
        fun busyReason(project: Project): String? = EP_NAME.extensionList.firstNotNullOfOrNull { it.busyReason(project) }
    }
}
