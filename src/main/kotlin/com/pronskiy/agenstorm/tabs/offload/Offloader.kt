package com.pronskiy.agenstorm.tabs.offload

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.pronskiy.agenstorm.tabs.ProjectTabsModel
import com.pronskiy.agenstorm.tabs.ProjectTabsModel.Companion.keyOf

/** What the offload rule needs from the settings. */
data class OffloadSettings(val enabled: Boolean, val idleMs: Long, val maxLoaded: Int)

/**
 * Step P2.5. One sweep: ask the guards, ask [OffloadPolicy], then for every chosen project mark its tab as
 * offloaded, close it, and take the mark back if the close did not happen. The guard is asked again right before
 * each close, because a command can start between the two. Every collaborator is a function so the tests can run
 * a sweep over fake projects; [ProjectOffloadService] supplies the real ones. Runs on the EDT.
 */
class Offloader(
    private val model: ProjectTabsModel,
    private val settings: () -> OffloadSettings,
    private val activeKey: () -> String?,
    private val busyReason: (Project) -> String?,
    private val close: (Project) -> Boolean,
    private val clock: () -> Long,
    private val onOffloaded: (name: String, reason: Reason) -> Unit,
) {

    enum class Reason { IDLE, CAP, MANUAL }

    /** Offloads what the rule picks among [loaded]; returns the keys that were closed. */
    fun sweep(loaded: List<Project>): List<String> {
        val settings = settings()
        val now = clock()
        val candidates = loaded.map { project ->
            val busy = busyReason(project)
            if (busy != null) LOG.debug("${project.name} stays loaded: $busy")
            OffloadPolicy.Candidate(keyOf(project), model.lastActive(keyOf(project)), busy = busy != null)
        }
        val chosen = OffloadPolicy.choose(candidates, activeKey(), now, settings.idleMs, settings.maxLoaded, settings.enabled)
        val byKey = loaded.associateBy(::keyOf)
        val closed = mutableListOf<String>()
        for (key in chosen) {
            val project = byKey[key] ?: continue
            if (project.isDisposed) continue
            val name = project.name
            val busy = busyReason(project)
            if (busy != null) {
                LOG.info("Not offloading $name after all: $busy")
                continue
            }
            val reason = if ((model.lastActive(key) ?: now) + settings.idleMs <= now) Reason.IDLE else Reason.CAP
            if (closeMarked(project, now, reason)) closed += key
        }
        return closed
    }

    /** Offload Project from the context menu: the busy reason when the project must stay, null once it is closed. */
    fun offload(project: Project): String? {
        busyReason(project)?.let { return it }
        closeMarked(project, clock(), Reason.MANUAL)
        return null
    }

    /** Mark, close, and take the mark back if the close did not happen. */
    private fun closeMarked(project: Project, now: Long, reason: Reason): Boolean {
        val name = project.name
        val key = keyOf(project)
        model.markOffloaded(project, now)
        if (!close(project)) {
            model.unmarkOffloaded(key)
            LOG.warn("Closing $name was refused; its tab stays an ordinary one")
            return false
        }
        LOG.info("Offloaded $name ($reason)")
        onOffloaded(name, reason)
        return true
    }

    private companion object {
        val LOG = logger<Offloader>()
    }
}
