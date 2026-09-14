package com.pronskiy.agenstorm.projectview

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Step O2.1: the name a duplicate opens with. */
class CopyNameSuggesterTest : BasePlatformTestCase() {

    private fun suggest(name: String, vararg siblings: String, isDirectory: Boolean = false) =
        CopyNameSuggester.suggest(name, siblings.toSet(), isDirectory)

    fun testTheFirstCopyIsTwo() {
        assertEquals("Client 2.php", suggest("Client.php", "Client.php"))
    }

    fun testTheCounterKeepsGoingRatherThanNesting() {
        assertEquals("Client 3.php", suggest("Client.php", "Client.php", "Client 2.php"))
        assertEquals("Client 4.php", suggest("Client.php", "Client.php", "Client 2.php", "Client 3.php"))
    }

    fun testDuplicatingACopyContinuesItsOwnCount() {
        assertEquals("Client 3.php", suggest("Client 2.php", "Client.php", "Client 2.php"))
    }

    fun testTheExtensionStaysAtTheEnd() {
        assertEquals("archive.tar 2.gz", suggest("archive.tar.gz", "archive.tar.gz"))
    }

    fun testADotFileCountsAfterTheWholeName() {
        assertEquals(".gitignore 2", suggest(".gitignore", ".gitignore"))
    }

    fun testANameWithoutAnExtension() {
        assertEquals("LICENSE 2", suggest("LICENSE", "LICENSE"))
    }

    fun testADirectoryIsNotSplitOnItsDot() {
        assertEquals("some.dir 2", suggest("some.dir", "some.dir", isDirectory = true))
    }

    fun testAGapInTheNumbersIsUsed() {
        assertEquals("Client 3.php", suggest("Client.php", "Client.php", "Client 2.php", "Client 4.php"))
    }
}
