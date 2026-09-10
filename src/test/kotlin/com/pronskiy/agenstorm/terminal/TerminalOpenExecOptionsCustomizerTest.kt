package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.EelOsFamily
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Step G1.4: a local terminal gets the shim directory plus the endpoint's port and token; the feature
 * toggle and a shell that runs over Eel leave the terminal alone.
 *
 * Step K1.2: the same terminal gets `$EDITOR` and `$VISUAL` pointed at the edit shim, forced past the rc
 * files, with the editor they displaced kept as the shim's fallback. The two features are independent, so
 * each one's footprint is asserted with the other one off.
 */
class TerminalOpenExecOptionsCustomizerTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            project.service<OpenRequestServer>().stop()
            NioFiles.deleteRecursively(service<OpenShimScriptHolder>().binDir)
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testLocalTerminalGetsTheShimDirectoryAndTheEndpoint() {
        if (SystemInfo.isWindows) return

        val shim = TerminalOpenExecOptionsCustomizer.shimFor(project, LocalEelDescriptor)!!

        val server = project.service<OpenRequestServer>()
        assertEquals(service<OpenShimScriptHolder>().binDir, shim.pathEntry)
        assertEquals(server.token, shim.token)
        assertEquals("the endpoint has to be listening before the shim can reach it", server.port, shim.port)
        assertTrue(shim.port > 0)
        assertTrue(Files.isExecutable(shim.pathEntry!!.resolve("open")))
    }

    fun testEveryConfiguredCommandNameLandsInTheShimDirectory() {
        if (SystemInfo.isWindows) return
        AgenstormSettings.getInstance().state.terminalOpenCommandNames = "open, e"

        val shim = TerminalOpenExecOptionsCustomizer.shimFor(project, LocalEelDescriptor)!!

        val binDir = shim.pathEntry!!
        assertTrue(Files.isExecutable(binDir.resolve("open")))
        assertTrue(Files.isExecutable(binDir.resolve("e")))
    }

    fun testFeatureOffBindsNothingAndShimsNothing() {
        AgenstormSettings.getInstance().state.terminalOpenEnabled = false
        AgenstormSettings.getInstance().state.terminalEditorEnabled = false

        assertNull(TerminalOpenExecOptionsCustomizer.shimFor(project, LocalEelDescriptor))
        assertEquals(-1, project.service<OpenRequestServer>().port)
        assertFalse(Files.exists(service<OpenShimScriptHolder>().binDir))
    }

    fun testAShellOverEelIsNotShimmed() {
        assertNull(TerminalOpenExecOptionsCustomizer.shimFor(project, RemoteDescriptor))
        assertEquals(-1, project.service<OpenRequestServer>().port)
    }

    fun testTheEditorBridgeGetsTheShimsAbsolutePath() {
        if (SystemInfo.isWindows) return

        val shim = TerminalOpenExecOptionsCustomizer.shimFor(project, LocalEelDescriptor)!!

        val editor = shim.editor ?: throw AssertionError("the editor bridge is on by default")
        assertEquals(
            service<OpenShimScriptHolder>().binDir.resolve(OpenShimScriptHolder.EDIT_SHIM_NAME).toString(),
            editor,
        )
        assertTrue("\$EDITOR must carry a path, not a name", Files.isExecutable(Path.of(editor)))
    }

    fun testTheEditorBridgeAloneKeepsTheDirectoryOffPath() {
        if (SystemInfo.isWindows) return
        AgenstormSettings.getInstance().state.terminalOpenEnabled = false

        val shim = TerminalOpenExecOptionsCustomizer.shimFor(project, LocalEelDescriptor)!!

        assertNull("nothing shadows `open`, so the directory has no business on PATH", shim.pathEntry)
        assertNotNull(shim.editor)
        assertTrue("the shim still needs the endpoint", shim.port > 0)
        assertFalse(Files.exists(service<OpenShimScriptHolder>().binDir.resolve("open")))
    }

    fun testTheEditorBridgeOffLeavesTheEditorVariablesAlone() {
        if (SystemInfo.isWindows) return
        AgenstormSettings.getInstance().state.terminalEditorEnabled = false

        val shim = TerminalOpenExecOptionsCustomizer.shimFor(project, LocalEelDescriptor)!!

        assertNull(shim.editor)
        assertNull(shim.editorFallback)
        assertFalse(Files.exists(service<OpenShimScriptHolder>().binDir.resolve(OpenShimScriptHolder.EDIT_SHIM_NAME)))
    }

    fun testTheDisplacedEditorIsWhatTheLoginShellSaidVisualFirst() {
        val binDir = service<OpenShimScriptHolder>().binDir

        assertEquals("mate -w", TerminalOpenExecOptionsCustomizer.displacedEditor(mapOf("VISUAL" to " mate -w ", "EDITOR" to "vim"), binDir))
        assertEquals("vim", TerminalOpenExecOptionsCustomizer.displacedEditor(mapOf("EDITOR" to "vim"), binDir))
        assertEquals("vim", TerminalOpenExecOptionsCustomizer.displacedEditor(mapOf("VISUAL" to "   ", "EDITOR" to "vim"), binDir))
        assertNull(TerminalOpenExecOptionsCustomizer.displacedEditor(emptyMap(), binDir))
    }

    fun testOurOwnShimIsNeverItsOwnFallback() {
        val binDir = service<OpenShimScriptHolder>().binDir
        val ours = binDir.resolve(OpenShimScriptHolder.EDIT_SHIM_NAME).toString()

        assertNull(TerminalOpenExecOptionsCustomizer.displacedEditor(mapOf("EDITOR" to ours), binDir))
        assertEquals(
            "a second look at EDITOR still finds the user's own",
            "vim",
            TerminalOpenExecOptionsCustomizer.displacedEditor(mapOf("VISUAL" to ours, "EDITOR" to "vim"), binDir),
        )
    }

    /** A WSL or SSH shell: it would see the PATH entry but could never reach the IDE's loopback port. */
    private object RemoteDescriptor : EelDescriptor {
        override val name: String = "test-remote"
        override val osFamily: EelOsFamily = EelOsFamily.Posix
    }
}
