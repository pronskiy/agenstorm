package com.pronskiy.agenstorm.scratch

import com.intellij.ide.scratch.ScratchFileTypeFilter
import com.intellij.openapi.fileTypes.FileType
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Trims the New Scratch File popup to the file types in `AgenstormSettings.State.scratchAllowedFileTypes`
 * (internal `FileType.name`s). Off switch: prohibits nothing, so the platform shows its full list again
 * without a restart. Registered in `agenstorm-scratch.xml` against the internal `scratchLanguageFilter` EP.
 */
class AllowlistScratchFilter : ScratchFileTypeFilter {

    override fun isProhibited(type: FileType): Boolean {
        val state = AgenstormSettings.getInstance().state
        if (!state.scratchFilterEnabled) return false
        return type.name !in state.scratchAllowedFileTypes
    }
}
