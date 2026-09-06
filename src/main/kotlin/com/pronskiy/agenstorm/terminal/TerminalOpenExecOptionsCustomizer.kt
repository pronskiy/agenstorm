package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.pronskiy.agenstorm.core.AgenstormSettings
import org.jetbrains.plugins.terminal.startup.MutableShellExecOptions
import org.jetbrains.plugins.terminal.startup.ShellExecOptionsCustomizer
import java.nio.file.Path

/**
 * Step G1.4. Puts the generated shim directory in front of a new terminal's PATH and tells the shim which
 * endpoint to talk to.
 *
 * `prependEntryToPATH` rather than a `PATH=` entry in the environment map: the terminal applies it through
 * `_INTELLIJ_FORCE_PREPEND_PATH` *after* the user's rc files have run, so a `.zshrc` that rebuilds PATH
 * from scratch does not push the shim off the front.
 *
 * Called on a background thread without a read lock, which is why the script is written here rather than in
 * a startup activity. Nothing is touched when the feature is off, on Windows (the shim is POSIX `sh`), or
 * when the shell runs over Eel — a WSL or SSH shell cannot reach the IDE's loopback port.
 */
class TerminalOpenExecOptionsCustomizer : ShellExecOptionsCustomizer {

    override fun customizeExecOptions(project: Project, shellExecOptions: MutableShellExecOptions) {
        val shim = shimFor(project, shellExecOptions.eelDescriptor) ?: return
        shellExecOptions.prependEntryToPATH(shim.binDir)
        shellExecOptions.setEnvironmentVariable(OpenRequestServer.PORT_ENV, shim.port.toString())
        shellExecOptions.setEnvironmentVariable(OpenRequestServer.TOKEN_ENV, shim.token)
    }

    /** What one terminal needs to reach this project's endpoint. */
    data class Shim(val binDir: Path, val port: Int, val token: String)

    companion object {
        /**
         * Installs the shim for this terminal and returns what to put in its environment, or null when the
         * terminal must be left alone. Separate from [customizeExecOptions] because `MutableShellExecOptions`
         * is a sealed interface no test outside the terminal plugin can implement.
         */
        fun shimFor(project: Project, eelDescriptor: EelDescriptor): Shim? {
            val state = AgenstormSettings.getInstance().state
            if (!state.terminalOpenEnabled || SystemInfo.isWindows || project.isDisposed) return null
            if (eelDescriptor != LocalEelDescriptor) return null
            return try {
                val names = OpenShimScriptHolder.parseCommandNames(state.terminalOpenCommandNames)
                val binDir = service<OpenShimScriptHolder>().install(names) ?: return null
                val server = project.service<OpenRequestServer>()
                val port = server.start()
                if (port <= 0) return null
                TerminalOpenNotice.showOnce(project)
                Shim(binDir, port, server.token)
            } catch (e: Exception) {
                // A terminal must open even when the shim cannot be installed.
                LOG.warn("Agenstorm: not shimming `open` in this terminal", e)
                null
            }
        }

        private val LOG = logger<TerminalOpenExecOptionsCustomizer>()
    }
}
