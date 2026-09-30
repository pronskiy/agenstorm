package com.pronskiy.agenstorm.worktrees.setup

import com.intellij.openapi.util.io.NioFiles
import com.pronskiy.agenstorm.worktrees.setup.SetupConfig.Setup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Step T2.5: Cursor's `worktrees.json`, and the command line a setup runs as. */
class SetupConfigTest {

    private val dir = Path.of("/r/.cursor")

    @Test
    fun aListOfCommandsAndThePlatformKeyWins() {
        val json = """{ "setup-worktree": ["echo any"], "setup-worktree-unix": ["composer install", " ", "cp \"${'$'}ROOT_WORKTREE_PATH/.env\" .env"] }"""

        assertEquals(Setup.Commands(listOf("composer install", "cp \"\$ROOT_WORKTREE_PATH/.env\" .env")), SetupConfig.parse(json, dir))
        assertEquals(Setup.Commands(listOf("echo any")), SetupConfig.parse(json, dir, windows = true))
    }

    @Test
    fun aStringIsAScriptRelativeToTheFile() {
        assertEquals(Setup.Script(Path.of("/r/.cursor/setup.sh")), SetupConfig.parse("""{ "setup-worktree": "setup.sh" }""", dir))
        assertEquals(Setup.Script(Path.of("/r/scripts/setup.sh")), SetupConfig.parse("""{ "setup-worktree-unix": "../scripts/setup.sh" }""", dir))
    }

    @Test
    fun nothingUsableIsNoSetup() {
        assertNull(SetupConfig.parse("""{ "setup-worktree-windows": ["dir"] }""", dir))
        assertNull(SetupConfig.parse("""{ "setup-worktree": [] }""", dir))
        assertNull(SetupConfig.parse("""{ "setup-worktree": 42 }""", dir))
        assertNull(SetupConfig.parse("not json", dir))
        assertNull(SetupConfig.parse("[]", dir))
    }

    @Test
    fun cursorsFileComesFirstThenOurs() {
        val main = Files.createTempDirectory("setup").toRealPath()
        try {
            assertNull(SetupConfig.find(main))
            Files.createDirectories(main.resolve(".agenstorm"))
            Files.writeString(main.resolve(".agenstorm/worktrees.json"), "{}")
            assertEquals(main.resolve(".agenstorm/worktrees.json"), SetupConfig.find(main))
            Files.createDirectories(main.resolve(".cursor"))
            Files.writeString(main.resolve(".cursor/worktrees.json"), "{}")
            assertEquals(main.resolve(".cursor/worktrees.json"), SetupConfig.find(main))
        } finally {
            NioFiles.deleteRecursively(main)
        }
    }

    @Test
    fun commandsRunInTheWorktreeStopAtTheFirstFailureAndSeeBothPaths() {
        val main = Path.of("/r")
        val worktree = Path.of("/r/.worktrees/fix-login")

        val unix = SetupRunner.commandLine(Setup.Commands(listOf("composer install", "npm ci")), main, worktree, windows = false)
        assertEquals(listOf("/bin/sh", "-c", "set -e\ncomposer install\nnpm ci"), unix.getCommandLineList(null))
        assertEquals(worktree, unix.workingDirectory)
        assertEquals("/r", unix.environment["ROOT_WORKTREE_PATH"])
        assertEquals("fix-login", unix.environment["AGENSTORM_WORKTREE_NAME"])
        assertEquals(worktree.toString(), unix.environment["AGENSTORM_WORKTREE_PATH"])

        assertEquals(listOf("/bin/sh", "/r/setup.sh"), SetupRunner.commandLine(Setup.Script(Path.of("/r/setup.sh")), main, worktree, windows = false).getCommandLineList(null))
        assertEquals(listOf("cmd.exe", "/c", "a && b"), SetupRunner.commandLine(Setup.Commands(listOf("a", "b")), main, worktree, windows = true).getCommandLineList(null))
    }
}
