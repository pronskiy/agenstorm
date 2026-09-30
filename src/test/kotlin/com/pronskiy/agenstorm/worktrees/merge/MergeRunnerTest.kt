package com.pronskiy.agenstorm.worktrees.merge

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.NioFiles
import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.LockState
import com.pronskiy.agenstorm.worktrees.cleanup.WorktreeRemover.GitResult
import com.pronskiy.agenstorm.worktrees.merge.MergePlan.Plan
import com.pronskiy.agenstorm.worktrees.merge.MergePlan.Strategy
import com.pronskiy.agenstorm.worktrees.merge.MergeRunner.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Step T4.3, against a real repository: both strategies land the work, and a conflict never half-merges the base. */
class MergeRunnerTest {

    private lateinit var root: Path
    private lateinit var main: Path
    private val runner = MergeRunner { dir, args -> run(dir, args) }

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

    private fun commit(dir: Path, file: String, text: String) {
        Files.writeString(dir.resolve(file), text)
        git(dir, "add", file)
        git(dir, "commit", "-q", "-m", "$file: $text")
    }

    private fun tree(path: Path, branch: String, isMain: Boolean = false) =
        Worktree(FileUtil.toSystemIndependentName(path.toString()), branch, isMain = isMain, isLocked = false, lockReason = null, createdAt = 0)

    private lateinit var wtPath: Path
    private val mainTree get() = tree(main, "main", isMain = true)
    private val wt get() = tree(wtPath, "fix-login")

    private fun plan(): Plan = MergePlan.plan(runner.facts(mainTree, wt, listOf(mainTree, wt), LockState.NONE, null))

    @Before
    fun setUp() {
        root = Files.createTempDirectory("merge").toRealPath()
        main = Files.createDirectories(root.resolve("app"))
        git(main, "init", "-q", "-b", "main")
        git(main, "config", "user.email", "t@example.com")
        git(main, "config", "user.name", "T")
        Files.writeString(main.resolve(".gitignore"), ".worktrees/\n")
        git(main, "add", ".gitignore")
        commit(main, "app.php", "one\n")
        wtPath = main.resolve(".worktrees/fix-login")
        git(main, "worktree", "add", "-q", "-b", "fix-login", wtPath.toString())
        git(main, "config", "branch.fix-login.agenstormBase", "main")
    }

    @After
    fun tearDown() = NioFiles.deleteRecursively(root)

    @Test
    fun squashLeavesTheWorkStagedOnTheBase() {
        commit(wtPath, "login.php", "fixed\n")
        commit(wtPath, "login.php", "fixed twice\n")
        Files.writeString(wtPath.resolve("notes.txt"), "wip")

        val plan = plan() as Plan.Ready
        assertEquals(Plan.Ready("fix-login", "main", mainTree.path, 2, listOf("notes.txt")), plan)
        assertEquals(Outcome.Merged(Strategy.SQUASH), runner.run(wtPath, plan, Strategy.SQUASH, "fix-login"))

        assertEquals("A  login.php\nA  notes.txt", git(main, "status", "--short", "--untracked-files=no"))
        assertEquals("wip: merge back fix-login", git(wtPath, "log", "-1", "--format=%s"))
    }

    @Test
    fun rebaseFastForwardsTheBase() {
        commit(main, "other.php", "on main\n")
        commit(wtPath, "login.php", "fixed\n")

        assertEquals(Outcome.Merged(Strategy.REBASE), runner.run(wtPath, plan() as Plan.Ready, Strategy.REBASE, "fix-login"))

        assertEquals(git(main, "rev-parse", "fix-login"), git(main, "rev-parse", "main"))
        assertEquals("", git(main, "status", "--short"))
    }

    @Test
    fun aSquashConflictLeavesTheBaseAsItWasAndTheConflictInTheWorktree() {
        commit(main, "app.php", "main's line\n")
        commit(wtPath, "app.php", "worktree's line\n")
        val before = git(main, "rev-parse", "HEAD")

        assertEquals(Outcome.Conflict(listOf("app.php")), runner.run(wtPath, plan() as Plan.Ready, Strategy.SQUASH, "fix-login"))

        assertEquals(before, git(main, "rev-parse", "HEAD"))
        assertEquals("", git(main, "status", "--short"))
        assertEquals("app.php", git(wtPath, "diff", "--name-only", "--diff-filter=U"))
    }

    @Test
    fun aRebaseConflictStaysInProgressInTheWorktree() {
        commit(main, "app.php", "main's line\n")
        commit(wtPath, "app.php", "worktree's line\n")
        val before = git(main, "rev-parse", "HEAD")

        assertEquals(Outcome.Conflict(listOf("app.php")), runner.run(wtPath, plan() as Plan.Ready, Strategy.REBASE, "fix-login"))

        assertEquals(before, git(main, "rev-parse", "HEAD"))
        assertEquals("", git(main, "status", "--short"))
        val gitDir = Path.of(git(wtPath, "rev-parse", "--absolute-git-dir"))
        assertTrue(Files.isDirectory(gitDir.resolve("rebase-merge")) || Files.isDirectory(gitDir.resolve("rebase-apply")))
    }

    @Test
    fun aDirtyBaseOrARemoteRecordedBaseAreReadRight() {
        commit(wtPath, "login.php", "fixed\n")
        git(main, "config", "branch.fix-login.agenstormBase", "origin/main")
        Files.writeString(main.resolve("app.php"), "edited on main\n")

        assertEquals(Plan.BaseDirty("main", mainTree.path, listOf("app.php")), plan())
    }
}
