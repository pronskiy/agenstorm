package com.pronskiy.agenstorm.core

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException

/**
 * Step U2.1. A project's work moves to another: a worktree switch has opened [to] in the window and is about to close
 * [from]. Called on the EDT after [to] has opened and before [from] closes — never when [from] stays open — and awaited,
 * so whatever has to happen before the close (U2.2 keeps the moving tmux sessions alive) has happened. An extension point
 * of Agenstorm's own rather than a message-bus topic, because a topic cannot be waited for; `worktrees/` calls it and
 * `terminal/` implements it in `agenstorm-terminal.xml`, so neither imports the other.
 */
interface ProjectHandOff {

    suspend fun handOff(from: Project, to: Project)

    companion object {
        val EP_NAME: ExtensionPointName<ProjectHandOff> = ExtensionPointName.create("com.pronskiy.agenstorm.projectHandOff")

        private val LOG = logger<ProjectHandOff>()

        /** Every hand-off, one after another; one that fails does not keep the switch from closing [from]. */
        suspend fun fire(from: Project, to: Project) {
            for (handOff in EP_NAME.extensionList) {
                try {
                    handOff.handOff(from, to)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Agenstorm: a hand-off from ${from.name} to ${to.name} failed", e)
                }
            }
        }
    }
}
