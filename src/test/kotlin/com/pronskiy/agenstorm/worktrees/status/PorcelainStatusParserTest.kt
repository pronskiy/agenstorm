package com.pronskiy.agenstorm.worktrees.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Step T3.1, on output captured from git 2.55 (`|` stands for NUL). */
class PorcelainStatusParserTest {

    private fun parse(captured: String) = PorcelainStatusParser.parse(captured.replace('|', '\u0000'))

    private val tracked = "# branch.oid 2e39107b2307e9029d9b0e8fb2aea79591f27722|# branch.head main|# branch.upstream origin/main|# branch.ab +1 -1|" +
        "1 .M N... 100644 100644 100644 78981922613b2afb6025042ff6bd878ac1994e85 78981922613b2afb6025042ff6bd878ac1994e85 a.txt|" +
        "2 R. N... 100644 100644 100644 61780798228d17af2d34fce4cfbdf35556832472 61780798228d17af2d34fce4cfbdf35556832472 R100 b 3.txt|1 fake.txt|" +
        "1 .M N... 100644 100644 100644 98eceb54958415238f876ab477baadb96f5d253c 98eceb54958415238f876ab477baadb96f5d253c c.txt|? d/|? n.txt|"

    @Test
    fun countsEveryKindOfChangeOnceAndReadsTheUpstream() {
        val porcelain = parse(tracked)

        // The rename's original path ("1 fake.txt") is its second field, not an entry of its own.
        assertEquals(PorcelainStatusParser.Porcelain("main", "origin/main", 1, 1, 5), porcelain)
        assertEquals(WorktreeStatus(5, 1, 1, "origin/main"), PorcelainStatusParser.status(porcelain, base = null, counts = null))
    }

    @Test
    fun unmergedCountsAndIgnoredDoesNot() {
        val porcelain = parse(
            "# branch.oid 8060500ac20837b7e88f8437b206e6aed4560314|# branch.head main|" +
                "u UU N... 100644 100644 100644 100644 5626abf0f72e58d7a153368ba57db4c673c0e171 2bdf67abb163a4ffb2d7f3f0880c9fe5068ce782 f719efd430d52bcfc8566a43b2eb655688d38871 f|! vendor/|",
        )

        assertEquals(PorcelainStatusParser.Porcelain("main", null, null, null, 1), porcelain)
    }

    @Test
    fun aCleanBranchWithoutUpstreamCountsAgainstItsRecordedBaseThenTheDefaultBranch() {
        val porcelain = parse("# branch.oid 2e39107b2307e9029d9b0e8fb2aea79591f27722|# branch.head topic|")

        assertEquals(PorcelainStatusParser.Porcelain("topic", null, null, null, 0), porcelain)
        assertEquals("main", PorcelainStatusParser.baseFor(porcelain, recordedBase = "main", defaultBranch = "origin/main"))
        assertEquals("origin/main", PorcelainStatusParser.baseFor(porcelain, recordedBase = null, defaultBranch = "origin/main"))
        assertNull(PorcelainStatusParser.baseFor(porcelain, recordedBase = null, defaultBranch = null))
        assertEquals(listOf("--left-right", "--count", "main...HEAD"), PorcelainStatusParser.countArgs("main"))
        assertEquals(WorktreeStatus(0, 3, 2, "main"), PorcelainStatusParser.status(porcelain, "main", PorcelainStatusParser.parseCounts("2\t3\n")))
    }

    @Test
    fun aGoneUpstreamFallsBackToTheBase() {
        val porcelain = parse("# branch.oid e408b6c2f536925e68806cde14e98e0d0536ed64|# branch.head gone|# branch.upstream origin/nothere|")

        assertEquals(PorcelainStatusParser.Porcelain("gone", "origin/nothere", null, null, 0), porcelain)
        assertEquals("main", PorcelainStatusParser.baseFor(porcelain, "main", null))
    }

    @Test
    fun noBaseForADetachedHeadATrackedBranchOrTheBaseItself() {
        val detached = parse("# branch.oid 2e39107b2307e9029d9b0e8fb2aea79591f27722|# branch.head (detached)|? n.txt|")
        val onMain = parse("# branch.oid (initial)|# branch.head main|")

        assertEquals(PorcelainStatusParser.Porcelain(null, null, null, null, 1), detached)
        assertNull(PorcelainStatusParser.baseFor(detached, "main", "origin/main"))
        assertNull(PorcelainStatusParser.baseFor(parse(tracked), "main", "origin/main"))
        assertNull(PorcelainStatusParser.baseFor(onMain, "main", null))
        assertEquals(WorktreeStatus(1, 0, 0, null), PorcelainStatusParser.status(detached, null, null))
    }

    @Test
    fun changedPathsNameEachEntryOnceWithTheirSpaces() {
        val output = tracked.replace('|', '\u0000') +
            "u UU N... 100644 100644 100644 100644 5626abf0f72e58d7a153368ba57db4c673c0e171 2bdf67abb163a4ffb2d7f3f0880c9fe5068ce782 f719efd430d52bcfc8566a43b2eb655688d38871 merge me.txt\u0000! vendor/\u0000"

        assertEquals(listOf("a.txt", "b 3.txt", "c.txt", "d/", "n.txt", "merge me.txt"), PorcelainStatusParser.changedPaths(output))
        assertEquals(emptyList<String>(), PorcelainStatusParser.changedPaths(""))
    }

    @Test
    fun unreadableCountsAreNoCounts() {
        assertNull(PorcelainStatusParser.parseCounts(""))
        assertNull(PorcelainStatusParser.parseCounts("fatal: ambiguous argument"))
        assertEquals(0 to 0, PorcelainStatusParser.parseCounts("0\t0"))
        val porcelain = parse("# branch.head topic|")
        assertEquals(WorktreeStatus(0, 0, 0, null), PorcelainStatusParser.status(porcelain, "main", counts = null))
    }
}
