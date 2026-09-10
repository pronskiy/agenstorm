package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.util.EnvironmentUtil
import com.pronskiy.agenstorm.core.AgenstormSettings
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.plugins.terminal.startup.MutableShellExecOptions
import org.jetbrains.plugins.terminal.startup.ShellExecOptionsCustomizer
import java.nio.file.Path

/**
 * Step G1.4, extended by step K1.2. Puts the generated shim directory in front of a new terminal's PATH,
 * points `$EDITOR` and `$VISUAL` at the edit shim, and tells both shims which endpoint to talk to.
 *
 * `prependEntryToPATH` rather than a `PATH=` entry in the environment map: the terminal applies it through
 * `_INTELLIJ_FORCE_PREPEND_PATH` *after* the user's rc files have run, so a `.zshrc` that rebuilds PATH
 * from scratch does not push the shim off the front. The editor variables need the same protection for the
 * same reason and get it the same way — `_INTELLIJ_FORCE_SET_<NAME>`, which the terminal's shell
 * integration exports once the rc files are done — so each one is set twice: plainly, which is all a
 * terminal with the integration switched off ever sees, and forced, which wins wherever it runs.
 *
 * Called on a background thread without a read lock, which is why the scripts are written here rather than
 * in a startup activity. Nothing is touched when both features are off, on Windows (the shims are POSIX
 * `sh`), or when the shell runs over Eel — a WSL or SSH shell cannot reach the IDE's loopback port.
 */
class TerminalOpenExecOptionsCustomizer : ShellExecOptionsCustomizer {

    override fun customizeExecOptions(project: Project, shellExecOptions: MutableShellExecOptions) {
        val shim = shimFor(project, shellExecOptions.eelDescriptor) ?: return
        shim.pathEntry?.let { shellExecOptions.prependEntryToPATH(it) }
        shellExecOptions.setEnvironmentVariable(OpenRequestServer.PORT_ENV, shim.port.toString())
        shellExecOptions.setEnvironmentVariable(OpenRequestServer.TOKEN_ENV, shim.token)
        val editor = shim.editor ?: return
        for (name in EDITOR_ENV) {
            shellExecOptions.setEnvironmentVariable(name, editor)
            shellExecOptions.setEnvironmentVariable(FORCE_SET_PREFIX + name, editor)
        }
        shim.editorFallback?.let {
            shellExecOptions.setEnvironmentVariable(OpenShimScriptHolder.EDITOR_FALLBACK_ENV, it)
        }
    }

    /** What one terminal needs to reach this project's endpoint. */
    data class Shim(
        /** The PATH entry, or null when no `open` name is shimmed and the directory has no business there. */
        val pathEntry: Path?,
        /** The edit shim's absolute path, or null when the editor bridge is off. */
        val editor: String?,
        val port: Int,
        val token: String,
        /** What the edit shim hands the file to when the IDE will not take it; null when there is nothing. */
        val editorFallback: String?,
    )

    companion object {
        /**
         * The two variables a program is expected to read for its editor, in the order everyone reads them:
         * `VISUAL` first — Claude Code included, which is why setting only `EDITOR` would not be enough.
         */
        val EDITOR_ENV: List<String> = listOf("VISUAL", "EDITOR")

        /**
         * What the terminal's own `shell-integrations/{zsh,bash,fish,powershell}` look for: every
         * `_INTELLIJ_FORCE_SET_FOO=BAR` in the environment is exported as `FOO=BAR` once the user's rc files
         * have run, and the carrier is unset. The same mechanism `prependEntryToPATH` is built on.
         */
        const val FORCE_SET_PREFIX: String = "_INTELLIJ_FORCE_SET_"

        /**
         * Installs the shims for this terminal and returns what to put in its environment, or null when the
         * terminal must be left alone. Separate from [customizeExecOptions] because `MutableShellExecOptions`
         * is a sealed interface no test outside the terminal plugin can implement.
         */
        fun shimFor(project: Project, eelDescriptor: EelDescriptor): Shim? {
            val state = AgenstormSettings.getInstance().state
            if (SystemInfo.isWindows || project.isDisposed) return null
            if (!state.terminalOpenEnabled && !state.terminalEditorEnabled) return null
            if (eelDescriptor != LocalEelDescriptor) return null
            return try {
                val names = when {
                    state.terminalOpenEnabled -> OpenShimScriptHolder.parseCommandNames(state.terminalOpenCommandNames)
                    else -> emptyList()
                }
                val binDir = service<OpenShimScriptHolder>()
                    .install(names, editShim = state.terminalEditorEnabled) ?: return null
                val server = project.service<OpenRequestServer>()
                val port = server.start()
                if (port <= 0) return null
                if (names.isNotEmpty()) TerminalOpenNotice.showOnce(project)
                val editor = binDir.resolve(OpenShimScriptHolder.EDIT_SHIM_NAME)
                    .takeIf { state.terminalEditorEnabled }
                val fallback = editor?.let { displacedEditor(EnvironmentUtil.getEnvironmentMap(), binDir) }
                if (editor != null) TerminalEditorNotice.showOnce(project, fallback)
                Shim(
                    // Only the `open` shims belong on PATH. With just the editor bridge on, the directory
                    // holds one script nothing ever looks up by name — `$EDITOR` carries its full path.
                    pathEntry = binDir.takeIf { names.isNotEmpty() },
                    editor = editor?.toString(),
                    port = port,
                    token = server.token,
                    editorFallback = fallback,
                )
            } catch (e: Exception) {
                // A terminal must open even when the shims cannot be installed.
                LOG.warn("Agenstorm: not shimming this terminal", e)
                null
            }
        }

        /**
         * The editor this terminal would have used without us, read from the login-shell environment the IDE
         * loaded at startup — which is where a user's `export EDITOR=…` ends up. It becomes the edit shim's
         * fallback, so a file the IDE declines still reaches the editor its owner chose.
         *
         * A value pointing into [binDir] is refused: that is one of our own shims, and handing a declined
         * edit back to it would loop.
         */
        @VisibleForTesting
        fun displacedEditor(environment: Map<String, String>, binDir: Path): String? = EDITOR_ENV
            .mapNotNull { environment[it]?.trim() }
            .firstOrNull { it.isNotEmpty() && !it.contains(binDir.toString()) }

        private val LOG = logger<TerminalOpenExecOptionsCustomizer>()
    }
}
