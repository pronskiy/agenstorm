package com.pronskiy.agenstorm.worktrees.status

import com.pronskiy.agenstorm.worktrees.status.StatusReader.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

/** Step T3.2: which git commands one read runs and how their answers make a status. */
class StatusReaderTest {

    private val calls = mutableListOf<Pair<Command, List<String>>>()
    private val answers = mutableMapOf<Command, List<String>?>()

    private val reader = StatusReader { _, command, args ->
        calls += command to args
        answers[command]
    }

    private fun status(head: String, vararg entries: String) =
        (listOf("# branch.oid 2e39107b2307e9029d9b0e8fb2aea79591f27722", head) + entries).joinToString("\u0000", postfix = "\u0000")

    @Test
    fun aTrackedBranchNeedsOneStatusAndNothingElse() {
        answers[Command.STATUS] = listOf(status("# branch.head main\u0000# branch.upstream origin/main\u0000# branch.ab +2 -0", "? n.txt"))

        assertEquals(WorktreeStatus(1, 2, 0, "origin/main"), reader.read(Path.of("/r")))
        assertEquals(listOf(Command.STATUS to PorcelainStatusParser.STATUS_ARGS), calls)
    }

    @Test
    fun anUntrackedBranchCountsAgainstItsRecordedBaseAndTheBasesAreReadOnce() {
        answers[Command.STATUS] = listOf(status("# branch.head Fix/Login"))
        answers[Command.CONFIG] = listOf("branch.Fix/Login.agenstormbase main", "branch.other.agenstormbase origin/main")
        answers[Command.REV_PARSE] = listOf("origin/main")
        answers[Command.REV_LIST] = listOf("1\t4")

        assertEquals(WorktreeStatus(0, 4, 1, "main"), reader.read(Path.of("/r/.worktrees/fix-login")))
        reader.read(Path.of("/r/.worktrees/fix-login"))

        assertEquals(1, calls.count { it.first == Command.CONFIG })
        assertEquals(listOf("--left-right", "--count", "main...HEAD"), calls.first { it.first == Command.REV_LIST }.second)

        reader.forgetBases()
        reader.read(Path.of("/r/.worktrees/fix-login"))
        assertEquals(2, calls.count { it.first == Command.CONFIG })
    }

    @Test
    fun noRecordedBaseFallsBackToTheDefaultBranch() {
        answers[Command.STATUS] = listOf(status("# branch.head worktree-agent"))
        answers[Command.CONFIG] = null
        answers[Command.REV_PARSE] = listOf("origin/main")
        answers[Command.REV_LIST] = listOf("0\t3")

        assertEquals(WorktreeStatus(0, 3, 0, "origin/main"), reader.read(Path.of("/r/.claude/worktrees/agent")))
    }

    @Test
    fun withoutAnyBaseThereIsNothingToCountAndAFailedStatusIsNoStatus() {
        answers[Command.STATUS] = listOf(status("# branch.head topic", "1 .M N... 100644 100644 100644 a a f"))
        answers[Command.REV_PARSE] = listOf("origin/HEAD")

        assertEquals(WorktreeStatus(1, 0, 0, null), reader.read(Path.of("/r")))
        assertEquals(0, calls.count { it.first == Command.REV_LIST })

        answers[Command.STATUS] = null
        assertNull(reader.read(Path.of("/r")))
    }

    @Test
    fun recordedBasesKeepTheBranchNamesCaseAndSkipOddLines() {
        assertEquals(
            mapOf("Fix/Login" to "main", "a.b" to "origin/main"),
            StatusReader.parseRecordedBases(listOf("branch.Fix/Login.agenstormbase main", "branch.a.b.agenstormbase origin/main", "branch.x.agenstormbase", "core.bare false")),
        )
    }
}
