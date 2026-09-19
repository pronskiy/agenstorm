package com.pronskiy.agenstorm.tabs.offload

import com.intellij.ide.FrameStateListener
import com.intellij.openapi.wm.IdeFrame
import com.pronskiy.agenstorm.tabs.ProjectTabsModel

/**
 * Step P2.2. A project is "active" when its frame was the last one brought to the front, so every activation is
 * recorded in [ProjectTabsModel.touch]; the offload policy reads those times back. Registered under
 * `applicationListeners` on `FrameStateListener.TOPIC` (public, app-level). [clock] exists for the tests.
 */
class TabsFrameListener(private val clock: () -> Long = System::currentTimeMillis) : FrameStateListener {

    override fun onFrameActivated(frame: IdeFrame) {
        val project = frame.project ?: return
        if (project.isDisposed || project.isDefault) return
        ProjectTabsModel.getInstance().touch(ProjectTabsModel.keyOf(project), clock())
    }
}
