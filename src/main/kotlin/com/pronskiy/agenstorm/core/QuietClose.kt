package com.pronskiy.agenstorm.core

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key

/**
 * Step U3.2. A project close Agenstorm makes on its own — offloading an idle project (Epic P), a worktree switch (Epic T),
 * removing a worktree — rather than one the user asked for. Nobody is looking at that window, so a feature that would
 * ask on close must not ask, and keeps instead of ending: a terminal still running becomes a background terminal (U3.2)
 * that comes back as a tab when the project opens again (U3.1). The closer wraps its `closeAndDispose` in [run].
 */
object QuietClose {

    private val KEY = Key.create<Boolean>("agenstorm.quietClose")

    fun <T> run(project: Project, close: () -> T): T {
        project.putUserData(KEY, true)
        try {
            return close()
        } finally {
            if (!project.isDisposed) project.putUserData(KEY, null)
        }
    }

    fun isQuiet(project: Project): Boolean = project.getUserData(KEY) == true
}
