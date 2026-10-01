package com.pronskiy.agenstorm.worktrees.carry

import com.intellij.openapi.util.io.NioFiles
import com.pronskiy.agenstorm.worktrees.cleanup.WorktreeRemover.GitResult
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Step T5.1, against a real repository: the new worktree gets the same uncommitted work, and the source keeps its own. */
class ChangeCarryTest {

    private lateinit var root: Path
    private lateinit var main: Path
    private lateinit var fresh: Path
    private val carry = ChangeCarry { dir, args -> run(dir, args) }

    private fun run(dir: Path, args: List<String>): GitResult {
        val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        return GitResult(process.waitFor() == 0, if (out.isEmpty()) emptyList() else out.split('\n').dropLastWhile { it.isEmpty() }, err)
    }

    private fun git(dir: Path, vararg args: String): String {
        val result = run(dir, args.toList())
        assertTrue("git ${args.joinToString(" ")}: ${result.error}", result.ok)
        return result.output.joinToString("\n")
    }

    private fun write(dir: Path, file: String, text: String) {
        val path = dir.resolve(file)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("change-carry")
        main = root.resolve("app")
        Files.createDirectories(main)
        git(main, "init", "-q", "-b", "main")
        git(main, "config", "user.email", "t@example.com")
        git(main, "config", "user.name", "T")
        for (file in listOf("a.txt", "b.txt", "c.txt", "d.txt")) write(main, file, "$file\n")
        Files.write(main.resolve("logo.bin"), byteArrayOf(0, 1, 2, 3))
        write(main, ".gitignore", ".env\n")
        git(main, "add", ".")
        git(main, "commit", "-q", "-m", "init")
        fresh = root.resolve("fresh")
        git(main, "worktree", "add", "-q", "-b", "fresh", fresh.toString(), "HEAD")
    }

    @After
    fun tearDown() = NioFiles.deleteRecursively(root)

    @Test
    fun stagedUnstagedRenamedDeletedBinaryAndUntrackedWorkArrivesAndTheSourceKeepsIt() {
        write(main, "a.txt", "a staged\n")
        git(main, "add", "a.txt")
        write(main, "b.txt", "b unstaged\n")
        Files.delete(main.resolve("c.txt"))
        git(main, "mv", "d.txt", "e.txt")
        Files.write(main.resolve("logo.bin"), byteArrayOf(9, 8, 7))
        write(main, "notes/todo.md", "untracked\n")
        write(main, ".env", "SECRET=1\n")
        val before = git(main, "status", "--porcelain")

        assertEquals("a, b, c, the rename, logo.bin, notes/todo.md — not the ignored .env", 6, carry.changes(main).size)
        val outcome = carry.carry(main, fresh)

        assertEquals(ChangeCarry.Outcome.Carried(tracked = true, untracked = 1, unstaged = false), outcome)
        assertEquals("the same work, staged as it was", before.replace("?? .env\n", ""), git(fresh, "status", "--porcelain"))
        assertEquals("b unstaged\n", Files.readString(fresh.resolve("b.txt")))
        assertArrayEquals(byteArrayOf(9, 8, 7), Files.readAllBytes(fresh.resolve("logo.bin")))
        assertFalse("ignored files are T2.2's business", Files.exists(fresh.resolve(".env")))
        assertEquals("the source keeps it", before, git(main, "status", "--porcelain"))
        assertEquals("no stash left behind", "", git(main, "stash", "list"))
    }

    @Test
    fun onlyUntrackedFilesOrNothingAtAll() {
        assertEquals(ChangeCarry.Outcome.Carried(tracked = false, untracked = 0, unstaged = false), carry.carry(main, fresh))
        write(main, "new.txt", "new\n")
        assertEquals(ChangeCarry.Outcome.Carried(tracked = false, untracked = 1, unstaged = false), carry.carry(main, fresh))
        assertEquals("new\n", Files.readString(fresh.resolve("new.txt")))
    }

    @Test
    fun aChangeThatCannotApplyFailsAndLeavesTheSourceAlone() {
        write(fresh, "a.txt", "already different\n")
        git(fresh, "commit", "-q", "-am", "diverge")
        write(main, "a.txt", "mine\n")

        assertTrue(carry.carry(main, fresh) is ChangeCarry.Outcome.Failed)
        assertEquals(" M a.txt", git(main, "status", "--porcelain"))
    }

    @Test
    fun aNestedRepositoryIsNotCopied() {
        assertEquals(listOf("a.txt", "dir/b.txt"), ChangeCarry.untrackedPaths("a.txt\u0000lib/\u0000dir/b.txt\u0000"))
    }
}
