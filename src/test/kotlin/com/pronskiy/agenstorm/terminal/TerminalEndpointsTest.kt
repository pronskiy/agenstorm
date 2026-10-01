package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Step U2.4: each running endpoint is listed for the shims, readable by its owner only, and unlisted when it stops. */
class TerminalEndpointsTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            NioFiles.deleteRecursively(TerminalEndpoints.dir)
        } finally {
            super.tearDown()
        }
    }

    fun testAnEndpointIsListedForItsOwnerOnlyAndUnlistedAgain() {
        if (SystemInfo.isWindows) return
        TerminalEndpoints.publish("/work/app", 51234, "s3cret")
        val file = TerminalEndpoints.fileFor("/work/app")

        assertEquals("51234\ts3cret\t/work/app\n", Files.readString(file))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(TerminalEndpoints.dir)))

        TerminalEndpoints.publish("/work/app", 51235, "n3w")
        assertEquals("51235\tn3w\t/work/app\n", Files.readString(file))

        TerminalEndpoints.withdraw("/work/app")
        assertFalse(Files.exists(file))
    }

    fun testAFolderTheLineCannotCarryIsNotListed() {
        assertNull(TerminalEndpoints.line("/work/a\tb", 1, "t"))
        assertNull(TerminalEndpoints.line("/work/app", -1, "t"))
        assertNotSame(TerminalEndpoints.fileFor("/work/app"), TerminalEndpoints.fileFor("/work/shop"))
        assertEquals(TerminalEndpoints.fileFor("/work/app"), TerminalEndpoints.fileFor("/work/app"))
    }

    fun testARunningServerListsItselfAndStoppingUnlistsIt() {
        if (SystemInfo.isWindows) return
        val server = project.getService(OpenRequestServer::class.java)
        try {
            val port = server.start()
            val file = TerminalEndpoints.fileFor(project.basePath!!)
            assertEquals("$port\t${server.token}\t${project.basePath}\n", Files.readString(file))
            server.stop()
            assertFalse(Files.exists(file))
        } finally {
            server.stop()
        }
    }
}
