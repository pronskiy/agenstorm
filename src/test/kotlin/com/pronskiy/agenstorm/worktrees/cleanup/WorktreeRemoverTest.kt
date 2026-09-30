package com.pronskiy.agenstorm.worktrees.cleanup

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.NioFiles
import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.LockState
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.Plan
import com.pronskiy.agenstorm.worktrees.cleanup.RemovalPlan.Risk
import com.pronskiy.agenstorm.worktrees.cleanup.WorktreeRemover.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Step T4.1, against a real repository: the facts git gives, and what removal leaves behind. */
class WorktreeRemoverTest {

    private lateinit var root: Path
    private lateinit var main: Path
    private val remover = WorktreeRemover { dir, args -> run(dir, args) }

    private fun run(dir: Path, args: List<String>): WorktreeRemover.GitResult {
        val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        return WorktreeRemover.GitResult(process.waitFor() == 0, if (out.isEmpty()) emptyList() else out.split('\n').dropLastWhile { it.isEmpty() }, err)
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

    private fun worktree(name: String, branch: String? = name, locked: String? = null): Worktree {
        val path = main.resolve(".worktrees/$name")
        if (branch != null) git(main, "worktree", "add", "-q", "-b", branch, path.toString()) else git(main, "worktree", "add", "-q", "--detach", path.toString())
        if (locked != null) git(main, "worktree", "lock", "--reason", locked, path.toString())
        return Worktree(FileUtil.toSystemIndependentName(path.toString()), branch, isMain = false, isLocked = locked != null, lockReason = locked, createdAt = 1)
    }

    private val mainTree get() = Worktree(FileUtil.toSystemIndependentName(main.toString()), "main", isMain = true, isLocked = false, lockReason = null, createdAt = 0)

    private fun branches() = git(main, "branch", "--format=%(refname:short)").lines().toSet()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("remove").toRealPath()
        main = Files.createDirectories(root.resolve("app"))
        git(main, "init", "-q", "-b", "main")
        git(main, "config", "user.email", "t@example.com")
        git(main, "config", "user.name", "T")
        Files.writeString(main.resolve(".gitignore"), ".worktrees/\n")
        commit(main, "README.md", "one")
    }

    @After
    fun tearDown() = NioFiles.deleteRecursively(root)

    @Test
    fun aCleanMergedWorktreeGoesWithItsBranch() {
        val wt = worktree("done")
        val facts = remover.facts(mainTree, wt, LockState.NONE, busyReason = null)

        assertEquals(RemovalPlan.Facts(false, "done", emptyList(), 0, "main", LockState.NONE, null, null), facts)
        val plan = RemovalPlan.plan(facts) as Plan.Ready
        assertEquals(Outcome.Removed("done", branchDeleted = true), remover.remove(main, wt, plan))
        assertFalse(Files.exists(Path.of(wt.path)))
        assertFalse("done" in branches())
    }

    @Test
    fun changesAndCommitsAreNamedThenForcedAwayWhileTheBranchKeepsTheCommits() {
        val wt = worktree("wip")
        commit(Path.of(wt.path), "feature.php", "work")
        Files.writeString(Path.of(wt.path, "README.md"), "edited")
        Files.writeString(Path.of(wt.path, "notes with space.txt"), "new")

        val plan = RemovalPlan.plan(remover.facts(mainTree, wt, LockState.NONE, null)) as Plan.Ready

        assertEquals(listOf(Risk.Changes(listOf("README.md", "notes with space.txt")), Risk.Unmerged(1, "main", "wip")), plan.risks)
        assertTrue(plan.force)
        assertEquals(Outcome.Removed("wip", branchDeleted = false), remover.remove(main, wt, plan))
        assertFalse(Files.exists(Path.of(wt.path)))
        assertTrue("wip" in branches())
    }

    @Test
    fun theRecordedBaseIsWhatCommitsAreCountedAgainst() {
        git(main, "branch", "release")
        commit(main, "later.txt", "on main only")
        val wt = worktree("fix")
        git(main, "config", "branch.fix.agenstormBase", "release")

        val facts = remover.facts(mainTree, wt, LockState.NONE, null)

        assertEquals("release", facts.base)
        assertEquals(1, facts.unmerged)
    }

    @Test
    fun aDetachedHeadCountsCommitsOnNoBranch() {
        val wt = worktree("loose", branch = null)
        commit(Path.of(wt.path), "loose.txt", "only here")

        val facts = remover.facts(mainTree, wt, LockState.NONE, null)

        assertEquals(null, facts.base)
        assertEquals(1, facts.unmerged)
        assertEquals(listOf(Risk.Unmerged(1, null, null)), (RemovalPlan.plan(facts) as Plan.Ready).risks)
    }

    @Test
    fun archivingCommitsEverythingPastAFailingHookAndKeepsTheBranch() {
        val wt = worktree("tables-q")
        Files.writeString(Path.of(wt.path, "README.md"), "edited")
        Files.writeString(Path.of(wt.path, "new.txt"), "new")
        val hook = main.resolve(".git/hooks/pre-commit")
        Files.writeString(hook, "#!/bin/sh\nexit 1\n")
        hook.toFile().setExecutable(true)

        val plan = RemovalPlan.archive(remover.facts(mainTree, wt, LockState.NONE, null)) as Plan.Ready

        assertEquals(Outcome.Removed("tables-q", branchDeleted = false), remover.remove(main, wt, plan))
        assertFalse(Files.exists(Path.of(wt.path)))
        assertEquals("wip: archive tables-q", git(main, "log", "-1", "--format=%s", "tables-q"))
        assertEquals("M\tREADME.md\nA\tnew.txt", git(main, "diff", "--name-status", "main", "tables-q"))
    }

    @Test
    fun aCleanWorktreeIsArchivedWithoutACommit() {
        val wt = worktree("idle")

        val plan = RemovalPlan.archive(remover.facts(mainTree, wt, LockState.NONE, null)) as Plan.Ready

        assertEquals(Outcome.Removed("idle", branchDeleted = false), remover.remove(main, wt, plan))
        assertEquals(git(main, "rev-parse", "main"), git(main, "rev-parse", "idle"))
    }

    @Test
    fun aLockIsLiftedToRemoveAndPutBackWhenRemovalFails() {
        val wt = worktree("usb", locked = "on a USB stick")
        val plan = RemovalPlan.plan(remover.facts(mainTree, wt, LockState.UNKNOWN, null)) as Plan.Ready
        assertEquals(listOf(Risk.Locked("on a USB stick")), plan.risks)

        Files.writeString(Path.of(wt.path, "untracked.txt"), "x")
        val unforced = plan.copy(force = false)
        val failed = remover.remove(main, wt, unforced)

        assertTrue(failed is Outcome.Failed)
        assertTrue(git(main, "worktree", "list", "--porcelain").contains("locked on a USB stick"))

        val forced = RemovalPlan.plan(remover.facts(mainTree, wt, LockState.UNKNOWN, null)) as Plan.Ready
        assertEquals(Outcome.Removed("usb", branchDeleted = true), remover.remove(main, wt, forced))
        assertFalse(Files.exists(Path.of(wt.path)))
    }
}
