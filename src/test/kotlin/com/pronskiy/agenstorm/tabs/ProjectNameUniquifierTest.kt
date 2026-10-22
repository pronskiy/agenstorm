package com.pronskiy.agenstorm.tabs

import com.pronskiy.agenstorm.tabs.ProjectNameUniquifier.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

/** A shown list of projects: same-named ones carry their parent folder, the rest keep their name alone. */
class ProjectNameUniquifierTest {

    @Test
    fun testANameUsedOnceIsShownAlone() {
        val names = ProjectNameUniquifier.displayNames(
            listOf(Entry("/a/one/alpha", "alpha", "/a/one/alpha"), Entry("/b/two/beta", "beta", "/b/two/beta")),
        )
        assertEquals(mapOf("/a/one/alpha" to "alpha", "/b/two/beta" to "beta"), names)
    }

    @Test
    fun testTwoProjectsSharingANameShowTheirParentFolder() {
        val names = ProjectNameUniquifier.displayNames(
            listOf(
                Entry("/work/SomeFolder/infrastructure", "infrastructure", "/work/SomeFolder/infrastructure"),
                Entry("/home/AnotherFolder/infrastructure", "infrastructure", "/home/AnotherFolder/infrastructure"),
            ),
        )
        assertEquals(
            mapOf(
                "/work/SomeFolder/infrastructure" to "SomeFolder/infrastructure",
                "/home/AnotherFolder/infrastructure" to "AnotherFolder/infrastructure",
            ),
            names,
        )
    }

    @Test
    fun testTheTwinIsPrefixedWhileTheRestKeepTheirNames() {
        val names = ProjectNameUniquifier.displayNames(
            listOf(
                Entry("/w/A/app", "app", "/w/A/app"),
                Entry("/w/B/app", "app", "/w/B/app"),
                Entry("/w/C/other", "other", "/w/C/other"),
            ),
        )
        assertEquals(mapOf("/w/A/app" to "A/app", "/w/B/app" to "B/app", "/w/C/other" to "other"), names)
    }

    @Test
    fun testThreeSharingANameAllGetTheirParent() {
        val names = ProjectNameUniquifier.displayNames(
            listOf(
                Entry("/w/A/app", "app", "/w/A/app"),
                Entry("/w/B/app", "app", "/w/B/app"),
                Entry("/w/C/app", "app", "/w/C/app"),
            ),
        )
        assertEquals(mapOf("/w/A/app" to "A/app", "/w/B/app" to "B/app", "/w/C/app" to "C/app"), names)
    }

    @Test
    fun testSameParentFolderGrowsThePrefixUntilTheNamesDiffer() {
        val names = ProjectNameUniquifier.displayNames(
            listOf(Entry("/x/dev/app", "app", "/x/dev/app"), Entry("/y/dev/app", "app", "/y/dev/app")),
        )
        assertEquals(mapOf("/x/dev/app" to "x/dev/app", "/y/dev/app" to "y/dev/app"), names)
    }

    @Test
    fun testOnlyTheEntriesStillSharingAFolderGoDeeper() {
        val names = ProjectNameUniquifier.displayNames(
            listOf(Entry("/x/dev/app", "app", "/x/dev/app"), Entry("/y/dev/app", "app", "/y/dev/app"), Entry("/z/other/app", "app", "/z/other/app")),
        )
        assertEquals(
            "other/app is told apart by its parent alone, the two dev/app need one folder more",
            mapOf("/x/dev/app" to "x/dev/app", "/y/dev/app" to "y/dev/app", "/z/other/app" to "other/app"),
            names,
        )
    }

    @Test
    fun testTwoOrMoreSkippedFoldersCollapseIntoAnEllipsis() {
        val names = ProjectNameUniquifier.displayNames(
            listOf(
                Entry("/w/A/app/.worktrees/fix-login", "app:fix-login", "/w/A/app/.worktrees/fix-login"),
                Entry("/w/B/app/.worktrees/fix-login", "app:fix-login", "/w/B/app/.worktrees/fix-login"),
            ),
        )
        assertEquals(
            mapOf("/w/A/app/.worktrees/fix-login" to "A/…/app:fix-login", "/w/B/app/.worktrees/fix-login" to "B/…/app:fix-login"),
            names,
        )
    }

    @Test
    fun testCollapsedNamesThatStillCollideShowEveryFolder() {
        val names = ProjectNameUniquifier.displayNames(
            listOf(Entry("/S/b/a/n", "n", "/S/b/a/n"), Entry("/S/T/b/a/n", "n", "/S/T/b/a/n"), Entry("/U/T/b/a/n", "n", "/U/T/b/a/n")),
        )
        assertEquals(
            "both of the first two collapse to S/…/n, so they spell the folders out; the third stays collapsed",
            mapOf("/S/b/a/n" to "S/b/a/n", "/S/T/b/a/n" to "S/T/b/a/n", "/U/T/b/a/n" to "U/…/n"),
            names,
        )
    }

    @Test
    fun testANestedProjectOfTheSameNameIsToldApartByItsParent() {
        val names = ProjectNameUniquifier.displayNames(
            listOf(Entry("/w/app", "app", "/w/app"), Entry("/w/app/x/app", "app", "/w/app/x/app")),
        )
        assertEquals(mapOf("/w/app" to "w/app", "/w/app/x/app" to "x/app"), names)
    }

    @Test
    fun testATrailingSlashDoesNotCountAsAFolder() {
        val names = ProjectNameUniquifier.displayNames(
            listOf(Entry("/w/A/app/", "app", "/w/A/app/"), Entry("/w/B/app", "app", "/w/B/app")),
        )
        assertEquals(mapOf("/w/A/app/" to "A/app", "/w/B/app" to "B/app"), names)
    }

    @Test
    fun testAShallowEntryIsPrefixedAsFarAsItsPathAllows() {
        val names = ProjectNameUniquifier.displayNames(
            listOf(Entry("/app", "app", "/app"), Entry("/w/B/app", "app", "/w/B/app")),
        )
        assertEquals("the root child has no parent: its name alone separates it from B/app", mapOf("/app" to "app", "/w/B/app" to "B/app"), names)
    }

    @Test
    fun testAnEntryWithoutAPathKeepsItsName() {
        val names = ProjectNameUniquifier.displayNames(
            listOf(Entry("alpha", "alpha", null), Entry("/w/B/alpha", "alpha", "/w/B/alpha")),
        )
        assertEquals(mapOf("alpha" to "alpha", "/w/B/alpha" to "B/alpha"), names)
    }

    @Test
    fun testNamesTheWorktreesAlreadyToldApartAreLeftAlone() {
        val names = ProjectNameUniquifier.displayNames(
            listOf(Entry("/w/app", "app:main", "/w/app"), Entry("/w/app/.worktrees/fix", "app:fix-login", "/w/app/.worktrees/fix")),
        )
        assertEquals(mapOf("/w/app" to "app:main", "/w/app/.worktrees/fix" to "app:fix-login"), names)
    }

    @Test
    fun testAnEmptyListGivesAnEmptyMap() {
        assertEquals(emptyMap<String, String>(), ProjectNameUniquifier.displayNames(emptyList()))
    }
}
