package com.pronskiy.agenstorm.projectview

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Step O1.5. What a committed name turns into: a file, a folder, and the folders on the way to either.
 */
class InlineCreateTest : BasePlatformTestCase() {

    private fun root(): VirtualFile = myFixture.tempDirFixture.findOrCreateDir("root")

    fun testAPlainFileIsCreatedInTheTargetFolder() {
        val created = InlineCreate.create(project, root(), "Client.php", asDirectory = false)

        assertTrue(created is PsiFile)
        assertNotNull(root().findChild("Client.php"))
    }

    fun testTheNewFileIsEmptyLikeTheStockActions() {
        // Decision 54: the platform's own New | File is directory.createFile(name) and nothing else.
        InlineCreate.create(project, root(), "Client.php", asDirectory = false)

        assertEquals("", String(root().findChild("Client.php")!!.contentsToByteArray()))
    }

    fun testANestedPathMakesTheFoldersOnTheWay() {
        val created = InlineCreate.create(project, root(), "src/Http/Client.php", asDirectory = false)

        assertTrue(created is PsiFile)
        assertNotNull(root().findFileByRelativePath("src/Http/Client.php"))
        assertTrue(root().findFileByRelativePath("src/Http")!!.isDirectory)
    }

    fun testADirectoryIsCreated() {
        val created = InlineCreate.create(project, root(), "Http", asDirectory = true)

        assertTrue(created is PsiDirectory)
        assertTrue(root().findChild("Http")!!.isDirectory)
    }

    fun testNestedDirectoriesAreCreated() {
        InlineCreate.create(project, root(), "src/Http/Client", asDirectory = true)

        assertTrue(root().findFileByRelativePath("src/Http/Client")!!.isDirectory)
    }

    fun testATrailingSeparatorAsksTheFileFieldForAFolder() {
        val created = InlineCreate.create(project, root(), "Http/", asDirectory = false)

        assertTrue(created is PsiDirectory)
        assertTrue(root().findChild("Http")!!.isDirectory)
    }

    fun testAnExistingFolderOnThePathIsReused() {
        InlineCreate.create(project, root(), "src/A.php", asDirectory = false)
        InlineCreate.create(project, root(), "src/B.php", asDirectory = false)

        assertNotNull(root().findFileByRelativePath("src/A.php"))
        assertNotNull(root().findFileByRelativePath("src/B.php"))
    }

    fun testANameTheIdeRefusesIsReportedNotThrown() {
        InlineCreate.create(project, root(), "Client.php", asDirectory = false)

        // The field's own check does not see two folders down, so a clash here has to fail softly.
        val second = InlineCreate.create(project, root(), "Client.php", asDirectory = false)

        assertNull(second)
        assertNotNull(root().findChild("Client.php"))
    }
}
