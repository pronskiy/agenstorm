package com.pronskiy.agenstorm.tabs

import com.intellij.ide.ReopenProjectAction
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.ProjectName
import com.pronskiy.agenstorm.core.ProjectNameProvider
import com.pronskiy.agenstorm.tabs.ui.RecentProjectRenderer
import java.awt.Component
import java.awt.Container

/** The "+" popup's recent projects: one line of icon, name and the branch as it is known now (Roman, 2026-10-19). */
class RecentProjectRendererTest : BasePlatformTestCase() {

    fun testAProjectIsOneLineOfIconNameAndTheBranchItIsGiven() {
        val action = ReopenProjectAction("/fake/agenstorm", "agenstorm", "agenstorm")
        val pane = RecentProjectRenderer.pane(action, "main", isSelected = false)
        pane.size = pane.preferredSize
        UIUtil.uiTraverser(pane).filter(Container::class.java).forEach(Container::doLayout)
        val labels = labelsOf(pane)
        assertEquals("no path, nothing but the name and the branch", listOf("agenstorm", "main"), labels.map { it.text })
        assertNotNull("the project's icon", labels.first().icon)
        assertNotNull("the branch has its icon", labels.last().icon)
        assertTrue("side by side", labels.last().x > labels.first().x + labels.first().width - 1)
        assertTrue("one line", labels.all { it.height > 0 && it.y < labels.first().height })
    }

    fun testWithNoBranchThereIsOnlyTheName() {
        val action = ReopenProjectAction("/fake/agenstorm", "agenstorm", "agenstorm")
        assertEquals(listOf("agenstorm"), labelsOf(RecentProjectRenderer.pane(action, null, isSelected = false)).map { it.text })
    }

    fun testTheFullPathIsTheTooltip() {
        val action = ReopenProjectAction("/fake/agenstorm", "agenstorm", "agenstorm")
        assertEquals("/fake/agenstorm", labelsOf(RecentProjectRenderer.pane(action, null, isSelected = false)).first().toolTipText)
    }

    fun testTwoProjectsSharingANameShowTheirParentFolder() {
        val first = ReopenProjectAction("/work/SomeFolder/infra", "infra", "infra")
        val second = ReopenProjectAction("/home/AnotherFolder/infra", "infra", "infra")
        val renderer = RecentProjectRenderer(listOf(first, second)) { null }
        assertEquals("SomeFolder/infra", renderer.nameFor(first))
        assertEquals("the other keeps its own parent", "AnotherFolder/infra", renderer.nameFor(second))
        val labels = labelsOf(RecentProjectRenderer.pane(first, null, isSelected = false, renderer.nameFor(first)))
        assertEquals("the row shows the prefixed name", listOf("SomeFolder/infra"), labels.map { it.text })
    }

    fun testANameNoOneSharesIsShownAlone() {
        val only = ReopenProjectAction("/work/alpha", "alpha", "alpha")
        val twin = ReopenProjectAction("/work/x/beta", "beta", "beta")
        val renderer = RecentProjectRenderer(listOf(only, twin)) { null }
        assertEquals(listOf("alpha", "beta"), listOf(renderer.nameFor(only), renderer.nameFor(twin)))
    }

    /** Roman, 2026-10-22: a worktree read `happy/.worktrees/test` under a "T"; it reads as on its tab, with its project's icon. */
    fun testALinkedWorktreeIsNamedAndIconedAfterItsProject() {
        ExtensionTestUtil.maskExtensions(ProjectNameProvider.EP_NAME, listOf(worktrees), testRootDisposable)
        val happy = ReopenProjectAction("/w/happy/.worktrees/test", "test", "test")
        val app = ReopenProjectAction("/w/app/.worktrees/test", "test", "test")
        val main = ReopenProjectAction("/w/happy", "happy", "happy")
        val renderer = RecentProjectRenderer(listOf(happy, app, main)) { null }
        assertEquals(
            "two worktrees of one folder name are told apart by their projects, so no folder goes in front",
            listOf("happy:test", "app:test", "happy"),
            listOf(happy, app, main).map(renderer::nameFor),
        )
        assertEquals("the worktree shows its main checkout's icon", "/w/happy", renderer.iconPathFor(happy))
        assertEquals("a main checkout shows its own", "/w/happy", renderer.iconPathFor(main))
    }

    /** Roman, 2026-10-22: beside another `infrastructure`, a worktree carries its own project's folder, not `.worktrees/`. */
    fun testAWorktreeOfASharedNameCarriesItsProjectsFolder() {
        ExtensionTestUtil.maskExtensions(ProjectNameProvider.EP_NAME, listOf(worktrees), testRootDisposable)
        val some = ReopenProjectAction("/w/SomeFolder/infrastructure", "infrastructure", "infrastructure")
        val another = ReopenProjectAction("/w/AnotherFolder/infrastructure", "infrastructure", "infrastructure")
        val fix = ReopenProjectAction("/w/SomeFolder/infrastructure/.worktrees/fix", "fix", "fix")
        val renderer = RecentProjectRenderer(listOf(some, another, fix)) { null }
        assertEquals(
            listOf("SomeFolder/infrastructure", "AnotherFolder/infrastructure", "SomeFolder/infrastructure:fix"),
            listOf(some, another, fix).map(renderer::nameFor),
        )
    }

    fun testAWorktreesPartIsDrawnDimmedAfterTheName() {
        val action = ReopenProjectAction("/w/SomeFolder/infrastructure/.worktrees/fix", "fix", "fix")
        val labels = labelsOf(RecentProjectRenderer.pane(action, "fix", isSelected = false, "SomeFolder/infrastructure:fix", part = "fix"))
        assertEquals(listOf("SomeFolder/infrastructure", ":fix", "fix"), labels.map { it.text })
        assertEquals("the part is in the branch's grey", UIUtil.getLabelInfoForeground(), labels[1].foreground)
    }

    fun testBranchesAreReadFromTheRecentProjectActions() {
        val actions = listOf(
            ReopenProjectAction("/fake/a", "a", "a", "main"),
            ReopenProjectAction("/fake/b", "b", "b"),
        )
        assertEquals(mapOf("/fake/a" to "main", "/fake/b" to null), ProjectTabActions.branchesOf(actions))
    }

    private fun labelsOf(component: Component): List<JBLabel> =
        UIUtil.uiTraverser(component).filter(JBLabel::class.java).toList()

    /** What the worktree feature answers for `<main>/.worktrees/<name>`: `<main's folder>:<name>`, in the main's folder. */
    private val worktrees = object : ProjectNameProvider {
        override fun name(basePath: String): ProjectName? =
            basePath.substringBefore("/.worktrees/", "").ifEmpty { null }?.let {
                ProjectName(it.substringAfterLast('/'), it, basePath.substringAfterLast('/'))
            }
    }
}
