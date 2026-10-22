package com.pronskiy.agenstorm.tabs

import com.pronskiy.agenstorm.core.ProjectName
import com.pronskiy.agenstorm.tabs.ProjectNameUniquifier.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

/** A shown list of projects: same-named ones carry their parent folder, the rest keep their name alone. */
class ProjectNameUniquifierTest {

    /** A project at [path] called [name]. */
    private fun project(path: String, name: String) = Entry(path, ProjectName(name, path))

    /** The worktree [part] at [path] of the project [name] in [main]. */
    private fun worktree(path: String, name: String, main: String, part: String) = Entry(path, ProjectName(name, main, part))

    private fun names(vararg entries: Entry) = ProjectNameUniquifier.displayNames(entries.toList())

    @Test
    fun testANameUsedOnceIsShownAlone() {
        assertEquals(
            mapOf("/a/one/alpha" to "alpha", "/b/two/beta" to "beta"),
            names(project("/a/one/alpha", "alpha"), project("/b/two/beta", "beta")),
        )
    }

    @Test
    fun testTwoProjectsSharingANameShowTheirParentFolder() {
        assertEquals(
            mapOf(
                "/work/SomeFolder/infrastructure" to "SomeFolder/infrastructure",
                "/home/AnotherFolder/infrastructure" to "AnotherFolder/infrastructure",
            ),
            names(project("/work/SomeFolder/infrastructure", "infrastructure"), project("/home/AnotherFolder/infrastructure", "infrastructure")),
        )
    }

    @Test
    fun testTheTwinIsPrefixedWhileTheRestKeepTheirNames() {
        assertEquals(
            mapOf("/w/A/app" to "A/app", "/w/B/app" to "B/app", "/w/C/other" to "other"),
            names(project("/w/A/app", "app"), project("/w/B/app", "app"), project("/w/C/other", "other")),
        )
    }

    @Test
    fun testThreeSharingANameAllGetTheirParent() {
        assertEquals(
            mapOf("/w/A/app" to "A/app", "/w/B/app" to "B/app", "/w/C/app" to "C/app"),
            names(project("/w/A/app", "app"), project("/w/B/app", "app"), project("/w/C/app", "app")),
        )
    }

    @Test
    fun testSameParentFolderGrowsThePrefixUntilTheNamesDiffer() {
        assertEquals(
            mapOf("/x/dev/app" to "x/dev/app", "/y/dev/app" to "y/dev/app"),
            names(project("/x/dev/app", "app"), project("/y/dev/app", "app")),
        )
    }

    @Test
    fun testOnlyTheEntriesStillSharingAFolderGoDeeper() {
        assertEquals(
            "other/app is told apart by its parent alone, the two dev/app need one folder more",
            mapOf("/x/dev/app" to "x/dev/app", "/y/dev/app" to "y/dev/app", "/z/other/app" to "other/app"),
            names(project("/x/dev/app", "app"), project("/y/dev/app", "app"), project("/z/other/app", "app")),
        )
    }

    @Test
    fun testTwoOrMoreSkippedFoldersCollapseIntoAnEllipsis() {
        assertEquals(
            mapOf("/w/A/src/main/app" to "A/…/app", "/w/B/src/main/app" to "B/…/app"),
            names(project("/w/A/src/main/app", "app"), project("/w/B/src/main/app", "app")),
        )
    }

    @Test
    fun testCollapsedNamesThatStillCollideShowEveryFolder() {
        assertEquals(
            "both of the first two collapse to S/…/n, so they spell the folders out; the third stays collapsed",
            mapOf("/S/b/a/n" to "S/b/a/n", "/S/T/b/a/n" to "S/T/b/a/n", "/U/T/b/a/n" to "U/…/n"),
            names(project("/S/b/a/n", "n"), project("/S/T/b/a/n", "n"), project("/U/T/b/a/n", "n")),
        )
    }

    @Test
    fun testANestedProjectOfTheSameNameIsToldApartByItsParent() {
        assertEquals(
            mapOf("/w/app" to "w/app", "/w/app/x/app" to "x/app"),
            names(project("/w/app", "app"), project("/w/app/x/app", "app")),
        )
    }

    @Test
    fun testATrailingSlashDoesNotCountAsAFolder() {
        assertEquals(
            mapOf("/w/A/app/" to "A/app", "/w/B/app" to "B/app"),
            names(project("/w/A/app/", "app"), project("/w/B/app", "app")),
        )
    }

    @Test
    fun testAShallowEntryIsPrefixedAsFarAsItsPathAllows() {
        assertEquals(
            "the root child has no parent: its name alone separates it from B/app",
            mapOf("/app" to "app", "/w/B/app" to "B/app"),
            names(project("/app", "app"), project("/w/B/app", "app")),
        )
    }

    @Test
    fun testAnEntryWithoutAPathKeepsItsName() {
        assertEquals(
            mapOf("alpha" to "alpha", "/w/B/alpha" to "B/alpha"),
            names(Entry("alpha", ProjectName("alpha", null)), project("/w/B/alpha", "alpha")),
        )
    }

    @Test
    fun testAProjectAndItsWorktreesAreOneProject() {
        assertEquals(
            "happy and happy:test are the same happy: nothing to tell apart",
            mapOf("/w/happy" to "happy", "/w/happy/.worktrees/test" to "happy:test"),
            names(project("/w/happy", "happy"), worktree("/w/happy/.worktrees/test", "happy", "/w/happy", "test")),
        )
    }

    @Test
    fun testWorktreesOfTwoProjectsKeepTheirProjectsNames() {
        assertEquals(
            mapOf("/w/happy/.worktrees/test" to "happy:test", "/w/app/.worktrees/test" to "app:test"),
            names(worktree("/w/happy/.worktrees/test", "happy", "/w/happy", "test"), worktree("/w/app/.worktrees/test", "app", "/w/app", "test")),
        )
    }

    /** Roman, 2026-10-22: Igor's two `infrastructure`s, one with a worktree — which one it belongs to shows. */
    @Test
    fun testAWorktreeCarriesTheFolderOfItsSameNamedProject() {
        assertEquals(
            mapOf(
                "/w/SomeFolder/infrastructure" to "SomeFolder/infrastructure",
                "/w/AnotherFolder/infrastructure" to "AnotherFolder/infrastructure",
                "/w/SomeFolder/infrastructure/.worktrees/fix" to "SomeFolder/infrastructure:fix",
            ),
            names(
                project("/w/SomeFolder/infrastructure", "infrastructure"),
                project("/w/AnotherFolder/infrastructure", "infrastructure"),
                worktree("/w/SomeFolder/infrastructure/.worktrees/fix", "infrastructure", "/w/SomeFolder/infrastructure", "fix"),
            ),
        )
    }

    @Test
    fun testSameNamedWorktreesOfSameNamedProjectsShowTheProjectsFoldersOnly() {
        assertEquals(
            "no .worktrees and no …: the folder that tells the projects apart, then the worktree",
            mapOf(
                "/w/SomeFolder/infrastructure/.worktrees/fix" to "SomeFolder/infrastructure:fix",
                "/w/AnotherFolder/infrastructure/.worktrees/fix" to "AnotherFolder/infrastructure:fix",
            ),
            names(
                worktree("/w/SomeFolder/infrastructure/.worktrees/fix", "infrastructure", "/w/SomeFolder/infrastructure", "fix"),
                worktree("/w/AnotherFolder/infrastructure/.worktrees/fix", "infrastructure", "/w/AnotherFolder/infrastructure", "fix"),
            ),
        )
    }

    @Test
    fun testAWorktreeShownWithoutItsMainCheckoutIsStillToldApart() {
        assertEquals(
            mapOf(
                "/w/SomeFolder/infra-fix" to "SomeFolder/infrastructure:infra-fix",
                "/w/AnotherFolder/infrastructure" to "AnotherFolder/infrastructure",
            ),
            names(
                worktree("/w/SomeFolder/infra-fix", "infrastructure", "/w/SomeFolder/infrastructure", "infra-fix"),
                project("/w/AnotherFolder/infrastructure", "infrastructure"),
            ),
        )
    }

    @Test
    fun testAnEmptyListGivesAnEmptyMap() {
        assertEquals(emptyMap<String, String>(), ProjectNameUniquifier.displayNames(emptyList()))
    }
}
