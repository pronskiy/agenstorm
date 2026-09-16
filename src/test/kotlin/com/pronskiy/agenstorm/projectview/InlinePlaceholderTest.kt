package com.pronskiy.agenstorm.projectview

import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.GroupByTypeComparator
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Decision 61: the row being named is a real, temporary node in the tree, so the tree makes room for it. What is
 * pinned here is that the node appears in the right folder only, and that the tree's own comparator puts it where
 * Roman asked — directly below the clicked row — whatever the sort mode.
 */
class InlinePlaceholderTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            InlinePlaceholders.clear()
        } finally {
            super.tearDown()
        }
    }

    private val settings = ViewSettings.DEFAULT

    private fun fileNode(path: String, text: String = "") =
        PsiFileNode(project, myFixture.addFileToProject(path, text), settings)

    private fun dirNode(path: String) =
        PsiDirectoryNode(project, PsiManager.getInstance(project).findDirectory(myFixture.tempDirFixture.findOrCreateDir(path))!!, settings)

    private fun modify(parent: AbstractTreeNode<*>, children: List<AbstractTreeNode<*>>) =
        InlinePlaceholderProvider().modify(parent, children, settings).toList()

    // --- which folder ---------------------------------------------------------------------------------------

    fun testNothingIsAddedWhileNoRowIsBeingNamed() {
        val docs = dirNode("root/docs")
        val children = listOf<AbstractTreeNode<*>>(fileNode("root/docs/a.md"))

        assertEquals(children, modify(docs, children))
    }

    fun testThePlaceholderAppearsInItsOwnFolderOnly() {
        val docs = dirNode("root/docs")
        val other = dirNode("root/other")
        InlinePlaceholders.show(InlinePlaceholder(project, docs.virtualFile!!, PlaceholderPosition.FIRST, anchor = null, isDirectory = false))

        assertEquals(1, modify(docs, emptyList()).count { it is PlaceholderNode })
        assertEquals(0, modify(other, emptyList()).count { it is PlaceholderNode })
    }

    fun testClearingRemovesIt() {
        val docs = dirNode("root/docs")
        InlinePlaceholders.show(InlinePlaceholder(project, docs.virtualFile!!, PlaceholderPosition.FIRST, anchor = null, isDirectory = false))
        InlinePlaceholders.clear()

        assertEquals(0, modify(docs, emptyList()).count { it is PlaceholderNode })
    }

    // --- where in the folder --------------------------------------------------------------------------------

    private fun sortedNames(nodes: List<AbstractTreeNode<*>>, byType: Boolean): List<String> {
        val comparator = GroupByTypeComparator(byType)
        nodes.forEach { it.update() }
        return nodes.sortedWith { a, b -> comparator.compare(a, b) }
            .map { if (it is PlaceholderNode) "<row>" else it.toString() }
    }

    private fun siblingsWithPlaceholderAfter(anchorName: String): List<AbstractTreeNode<*>> {
        val docs = dirNode("root/docs")
        val a = fileNode("root/docs/a.md")
        val anchor = fileNode("root/docs/$anchorName")
        val after = fileNode("root/docs/${anchorName}2")
        val z = fileNode("root/docs/z.md")
        InlinePlaceholders.show(InlinePlaceholder(project, docs.virtualFile!!, PlaceholderPosition.AFTER_ANCHOR, anchor, isDirectory = false))
        return modify(docs, listOf(z, after, anchor, a))
    }

    fun testTheRowSitsDirectlyBelowTheClickedFileSortedByName() {
        val names = sortedNames(siblingsWithPlaceholderAfter("shmest.md"), byType = false)

        assertEquals(names.indexOf("shmest.md") + 1, names.indexOf("<row>"))
    }

    fun testTheRowSitsDirectlyBelowTheClickedFileSortedByType() {
        val names = sortedNames(siblingsWithPlaceholderAfter("shmest.md"), byType = true)

        assertEquals(names.indexOf("shmest.md") + 1, names.indexOf("<row>"))
    }

    fun testUnderAClickedFolderTheRowIsItsFirstChild() {
        val docs = dirNode("root/docs")
        val sub = dirNode("root/docs/sub")
        InlinePlaceholders.show(InlinePlaceholder(project, docs.virtualFile!!, PlaceholderPosition.FIRST, anchor = null, isDirectory = false))

        val names = sortedNames(modify(docs, listOf(fileNode("root/docs/b.md"), sub, fileNode("root/docs/a.md"))), byType = false)

        assertEquals(0, names.indexOf("<row>"))
    }

    fun testWithoutAClickTheRowIsTheLastChild() {
        val docs = dirNode("root/docs")
        InlinePlaceholders.show(InlinePlaceholder(project, docs.virtualFile!!, PlaceholderPosition.LAST, anchor = null, isDirectory = false))

        val names = sortedNames(modify(docs, listOf(fileNode("root/docs/z.md"), fileNode("root/docs/a.md"))), byType = false)

        assertEquals(names.size - 1, names.indexOf("<row>"))
    }
}
