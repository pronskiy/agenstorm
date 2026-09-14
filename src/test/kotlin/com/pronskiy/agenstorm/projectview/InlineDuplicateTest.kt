package com.pronskiy.agenstorm.projectview

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Step O2.2: the copy itself. The naming is [CopyNameSuggesterTest]; this is what lands on disk. */
class InlineDuplicateTest : BasePlatformTestCase() {

    private fun root(): VirtualFile = myFixture.tempDirFixture.findOrCreateDir("root")

    fun testAFileIsCopiedNextToItselfWithItsContents() {
        val source = myFixture.addFileToProject("root/Client.php", "<?php\nclass Client {}\n")

        InlineDuplicate.duplicate(project, source, "Client 2.php")

        val copy = root().findChild("Client 2.php")
        assertNotNull(copy)
        assertEquals(String(source.virtualFile.contentsToByteArray()), String(copy!!.contentsToByteArray()))
        assertNotNull("the original stays", root().findChild("Client.php"))
    }

    fun testAFolderIsCopiedWithWhatIsInIt() {
        myFixture.addFileToProject("root/Http/Request.php", "<?php")
        myFixture.addFileToProject("root/Http/Response.php", "<?php")
        val source = PsiManager.getInstance(project).findDirectory(root().findChild("Http")!!)!!

        InlineDuplicate.duplicate(project, source, "Api")

        val copy = root().findChild("Api")
        assertNotNull(copy)
        assertTrue(copy!!.isDirectory)
        assertNotNull(copy.findChild("Request.php"))
        assertNotNull(copy.findChild("Response.php"))
    }

    fun testTheSuggestedNameIsTheOneThatLands() {
        val source = myFixture.addFileToProject("root/Client.php", "<?php")
        val siblings = root().children.mapTo(HashSet()) { it.name }

        val suggested = CopyNameSuggester.suggest(source.name, siblings, isDirectory = false)
        InlineDuplicate.duplicate(project, source, suggested)

        assertEquals("Client 2.php", suggested)
        assertNotNull(root().findChild("Client 2.php"))
    }
}
