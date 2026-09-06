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

/**
 * Step G1.4: a local terminal gets the shim directory plus the endpoint's port and token; the feature
 * toggle and a shell that runs over Eel leave the terminal alone.
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
        assertEquals(service<OpenShimScriptHolder>().binDir, shim.binDir)
        assertEquals(server.token, shim.token)
        assertEquals("the endpoint has to be listening before the shim can reach it", server.port, shim.port)
        assertTrue(shim.port > 0)
        assertTrue(Files.isExecutable(shim.binDir.resolve("open")))
    }

    fun testEveryConfiguredCommandNameLandsInTheShimDirectory() {
        if (SystemInfo.isWindows) return
        AgenstormSettings.getInstance().state.terminalOpenCommandNames = "open, e"

        val shim = TerminalOpenExecOptionsCustomizer.shimFor(project, LocalEelDescriptor)!!

        assertTrue(Files.isExecutable(shim.binDir.resolve("open")))
        assertTrue(Files.isExecutable(shim.binDir.resolve("e")))
    }

    fun testFeatureOffBindsNothingAndShimsNothing() {
        AgenstormSettings.getInstance().state.terminalOpenEnabled = false

        assertNull(TerminalOpenExecOptionsCustomizer.shimFor(project, LocalEelDescriptor))
        assertEquals(-1, project.service<OpenRequestServer>().port)
        assertFalse(Files.exists(service<OpenShimScriptHolder>().binDir))
    }

    fun testAShellOverEelIsNotShimmed() {
        assertNull(TerminalOpenExecOptionsCustomizer.shimFor(project, RemoteDescriptor))
        assertEquals(-1, project.service<OpenRequestServer>().port)
    }

    /** A WSL or SSH shell: it would see the PATH entry but could never reach the IDE's loopback port. */
    private object RemoteDescriptor : EelDescriptor {
        override val name: String = "test-remote"
        override val osFamily: EelOsFamily = EelOsFamily.Posix
    }
}
