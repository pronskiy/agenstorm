package com.pronskiy.agenstorm.worktrees.carry

import com.intellij.openapi.util.io.NioFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Steps T2.2 and T2.3: against a real repository, so git's own matching decides what `.worktreeinclude` and
 * `.gitignore` mean; and the cloner copies a file, a folder and a symbolic link without overwriting anything.
 */
class CarryOverTest {

    private lateinit var root: Path
    private lateinit var main: Path
    private lateinit var worktree: Path

    private fun git(vararg args: String): List<String>? {
        val process = ProcessBuilder(listOf("git", "-C", main.toString()) + args).redirectErrorStream(false).start()
        val out = process.inputStream.bufferedReader().readText()
        return if (process.waitFor() == 0) listOf(out) else null
    }

    private fun write(relative: String, text: String = relative) {
        val file = main.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun carry(skip: List<String> = listOf(".worktrees")) =
        CarryOver({ args -> git("ls-files", *args.toTypedArray()) }).run(main, worktree, skip)

    @Before
    fun setUp() {
        root = Files.createTempDirectory("carry").toRealPath()
        main = Files.createDirectories(root.resolve("main"))
        worktree = Files.createDirectories(root.resolve("main/.worktrees/new"))
        git("init", "-q")
        write(".gitignore", ".env\n.env.local\nvendor/\nconfig/secrets/\n*.key\n.worktrees/\n")
        write(".env.example")
        write("config/app.php")
        git("add", ".gitignore", ".env.example", "config/app.php")
        write(".env")
        write(".env.local")
        write("notes.txt")
        write("vendor/acme/Lib.php")
        write("config/secrets/db.json")
        write("app.key")
    }

    @After
    fun tearDown() {
        NioFiles.deleteRecursively(root)
    }

    @Test
    fun withoutAnIncludeFileTheIgnoredDotEnvFilesAreCarried() {
        assertEquals(listOf(".env", ".env.local"), carry())
        assertEquals(".env", Files.readString(worktree.resolve(".env")))
        assertFalse(Files.exists(worktree.resolve(".env.example")))
        assertFalse(Files.exists(worktree.resolve("vendor")))
    }

    @Test
    fun anIncludeFileNamesWhatIsCarriedAndOnlyIgnoredThingsQualify() {
        write(".worktreeinclude", "config/secrets/\n*.key\nnotes.txt\n")

        assertEquals(listOf("app.key", "config/secrets"), carry())
        assertEquals("config/secrets/db.json", Files.readString(worktree.resolve("config/secrets/db.json")))
        assertFalse("untracked but not ignored", Files.exists(worktree.resolve("notes.txt")))
        assertFalse("ignored but not included", Files.exists(worktree.resolve(".env")))
    }

    @Test
    fun somethingAlreadyInTheWorktreeIsNotOverwritten() {
        Files.writeString(worktree.resolve(".env"), "mine")

        assertEquals(listOf(".env.local"), carry())
        assertEquals("mine", Files.readString(worktree.resolve(".env")))
    }

    @Test
    fun theClonerCopiesFoldersAndKeepsSymbolicLinks() {
        write("tree/a.txt", "a")
        write("tree/sub/b.txt", "b")
        Files.createSymbolicLink(main.resolve("tree/link"), Path.of("a.txt"))

        assertTrue(TreeCloner().clone(main.resolve("tree"), worktree.resolve("tree")))
        assertEquals("b", Files.readString(worktree.resolve("tree/sub/b.txt")))
        assertTrue(Files.isSymbolicLink(worktree.resolve("tree/link")))
        assertFalse("never over something already there", TreeCloner().clone(main.resolve("tree"), worktree.resolve("tree")))
    }

    @Test
    fun whenCpFailsTheJavaCopyTakesOver() {
        write("tree/a.txt", "a")

        assertTrue(TreeCloner(exec = { false }).clone(main.resolve("tree"), worktree.resolve("tree")))
        assertEquals("a", Files.readString(worktree.resolve("tree/a.txt")))
    }
}
