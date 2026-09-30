package com.pronskiy.agenstorm.worktrees.setup

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Step T2.5. Starts a command in a terminal tab of [Project]. An extension point of Agenstorm's own,
 * `com.pronskiy.agenstorm.worktreeSetupTerminal`, so the implementation that needs the Terminal plugin lives with that
 * plugin's optional-dependency file and `worktrees/` never loads a terminal class itself.
 */
interface SetupTerminal {

    /** Opens a tab named [tabName] in [directory] with [env] and runs [command] there; false when it could not. Call on the EDT. */
    fun run(project: Project, directory: Path, env: Map<String, String>, tabName: String, command: String): Boolean

    companion object {
        val EP_NAME: ExtensionPointName<SetupTerminal> = ExtensionPointName.create("com.pronskiy.agenstorm.worktreeSetupTerminal")
    }
}
