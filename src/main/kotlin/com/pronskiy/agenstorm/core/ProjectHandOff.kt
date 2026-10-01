package com.pronskiy.agenstorm.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * Step U2.1. A project's work moves to another: a worktree switch has opened [to] in the window and is about to close
 * [from]. Published on the application bus on the EDT, after [to] has opened and before [from] closes — never when
 * [from] stays open. `worktrees/` publishes it and `terminal/` hands its running terminals over (U2.2), so neither has
 * to know the other.
 */
fun interface ProjectHandOff {

    fun handOff(from: Project, to: Project)

    companion object {
        val TOPIC: Topic<ProjectHandOff> = Topic.create("Agenstorm project hand-off", ProjectHandOff::class.java)

        fun fire(from: Project, to: Project) {
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).handOff(from, to)
        }
    }
}
