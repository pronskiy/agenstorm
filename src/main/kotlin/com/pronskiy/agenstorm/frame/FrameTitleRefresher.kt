package com.pronskiy.agenstorm.frame

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.IdeFrameEx

/** Applies a changed "hide file name" toggle to the frames that are already open. */
object FrameTitleRefresher {

    private val LOG = logger<FrameTitleRefresher>()

    /**
     * Clears the file part of every open project frame's title; the next editor switch recomputes it through
     * [ProjectOnlyFrameTitleBuilder]. Fails soft: a platform without this hook only logs a warning.
     */
    fun refreshOpenFrames() {
        for (project in ProjectManager.getInstance().openProjects) {
            try {
                (WindowManager.getInstance().getIdeFrame(project) as? IdeFrameEx)?.setFileTitle(null, null)
            } catch (e: LinkageError) {
                LOG.warn("Cannot refresh the frame title of ${project.name}", e)
            }
        }
    }
}
