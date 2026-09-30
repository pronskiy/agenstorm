package com.pronskiy.agenstorm.worktrees.ui

import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The folders holding worktrees leave the Project pane's directory nodes; everything else, and other node kinds, stay. */
class WorktreeFolderFilterTest : BasePlatformTestCase() {

    fun testTheWorktreeFolderIsDroppedAndTheRestKept() {
        val root = myFixture.tempDirFixture.findOrCreateDir("app")
        val worktrees = psiManager.findDirectory(myFixture.tempDirFixture.findOrCreateDir("app/.worktrees"))!!
        val src = psiManager.findDirectory(myFixture.tempDirFixture.findOrCreateDir("app/src"))!!
        val readme = psiManager.findFile(myFixture.tempDirFixture.createFile("app/README.md", "hi"))!!
        val parent = PsiDirectoryNode(project, psiManager.findDirectory(root)!!, null)
        val children: List<AbstractTreeNode<*>> = listOf(PsiDirectoryNode(project, worktrees, null), PsiDirectoryNode(project, src, null), PsiFileNode(project, readme, null))

        val kept = WorktreeFolderFilter { setOf(worktrees.virtualFile.path) }.modify(parent, children, null)

        assertEquals(listOf("src", "README.md"), kept.map { (it.value as com.intellij.psi.PsiFileSystemItem).name })
    }

    fun testNothingToHideLeavesTheChildrenAsTheyAre() {
        val src = psiManager.findDirectory(myFixture.tempDirFixture.findOrCreateDir("app/src"))!!
        val children: List<AbstractTreeNode<*>> = listOf(PsiDirectoryNode(project, src, null))

        assertSame(children, WorktreeFolderFilter { emptySet() }.modify(PsiDirectoryNode(project, src, null), children, null))
    }
}
