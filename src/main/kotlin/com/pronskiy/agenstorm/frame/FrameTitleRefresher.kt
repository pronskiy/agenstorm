package com.pronskiy.agenstorm.frame

import com.intellij.ide.ui.UISettings

/** Applies a changed "hide file name" toggle to the frames that are already open. */
object FrameTitleRefresher {

    /**
     * Makes every open frame recompute its title, so the toggle takes effect without waiting for the next
     * editor switch. A UI settings change is the public path into that: `FileEditorManagerImpl`'s
     * `UISettingsListener` calls `EditorsSplitters.updateFrameTitle()`, which asks the `FrameTitleBuilder`
     * service — [ProjectOnlyFrameTitleBuilder] while the feature is on — for both parts of the title again.
     * Turning the toggle off therefore brings the file name straight back, which clearing the file part could not.
     */
    fun refreshOpenFrames() {
        UISettings.getInstance().fireUISettingsChanged()
    }
}
