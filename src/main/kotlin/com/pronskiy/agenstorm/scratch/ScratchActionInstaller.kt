package com.pronskiy.agenstorm.scratch

import com.pronskiy.agenstorm.core.ActionSlot
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step B2.3. Holds the platform's `NewScratchFile` slot while the feature is on, and gives it back when it is
 * off — so the shortcut, the File → New entry and the editor popup item all keep working either way, and the
 * only difference is whose popup opens.
 */
object ScratchActionInstaller {

    const val NEW_SCRATCH_FILE_ACTION_ID = "NewScratchFile"

    private val slot = ActionSlot(NEW_SCRATCH_FILE_ACTION_ID) { NewScratchFileAction() }

    fun sync() {
        slot.sync(AgenstormSettings.getInstance().state.scratchFilterEnabled)
    }

    fun install() = slot.take()

    fun uninstall() = slot.giveBack()

    fun isInstalled(): Boolean = slot.isTaken()
}
