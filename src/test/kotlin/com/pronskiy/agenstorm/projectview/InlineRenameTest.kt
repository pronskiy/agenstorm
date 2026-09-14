package com.pronskiy.agenstorm.projectview

import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Step O1.5. The gate on what the inline row may rename (decision 55). The rename itself is the platform's;
 * what is pinned here is that the elements whose handlers do real work are handed back to them.
 */
class InlineRenameTest : BasePlatformTestCase() {

    private fun psiDir(path: String) =
        PsiManager.getInstance(project).findDirectory(myFixture.tempDirFixture.findOrCreateDir(path))!!

    fun testAPlainFileMayBeRenamedInline() {
        val file = myFixture.addFileToProject("root/Client.php", "<?php")

        assertTrue(InlineRename.canRenameInline(project, file))
    }

    fun testAMarkdownFileMayBeRenamedInline() {
        // Markdown registers its own rename handler, but it is a pass-through to the default one.
        val file = myFixture.addFileToProject("root/Notes.md", "# hi")

        assertTrue(InlineRename.canRenameInline(project, file))
    }

    fun testAPlainDirectoryMayBeRenamedInline() {
        assertTrue(InlineRename.canRenameInline(project, psiDir("root/Http")))
    }

    fun testAContentRootIsHandedBackToThePlatform() {
        val root = ProjectRootManager.getInstance(project).contentRoots.first()
        val directory = PsiManager.getInstance(project).findDirectory(root)!!

        assertFalse(InlineRename.canRenameInline(project, directory))
    }

    fun testSomethingThatIsNotAFileOrFolderIsHandedBack() {
        val file = myFixture.configureByText("Client.php", "<?php class <caret>Foo {}")
        val classElement = file.findElementAt(myFixture.caretOffset)!!

        assertFalse(InlineRename.canRenameInline(project, classElement))
    }

    fun testRenamingAFileChangesItsName() {
        val file = myFixture.addFileToProject("root/Client.php", "<?php")

        InlineRename.rename(project, file, "HttpClient.php")

        assertEquals("HttpClient.php", file.virtualFile.name)
    }
}
