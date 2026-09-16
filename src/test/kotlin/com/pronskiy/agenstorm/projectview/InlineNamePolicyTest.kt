package com.pronskiy.agenstorm.projectview

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Step O1.1: everything the inline field decides on its own — is this a usable name, how does it split into
 * folders, and how much of it is selected when the field opens.
 */
class InlineNamePolicyTest : BasePlatformTestCase() {

    private fun invalid(verdict: NameVerdict): NameVerdict.Invalid {
        assertTrue("expected Invalid, got $verdict", verdict is NameVerdict.Invalid)
        return verdict as NameVerdict.Invalid
    }

    // --- validate -------------------------------------------------------------------------------------

    fun testEmptyNameIsRejected() {
        for (typed in listOf("", "   ", "\t")) {
            assertEquals(
                InlineNamePolicy.ERROR_EMPTY,
                invalid(InlineNamePolicy.validate(InlineNameKind.NEW_FILE, typed, emptySet())).messageKey,
            )
        }
    }

    fun testPlainNameIsAccepted() {
        assertEquals(NameVerdict.Ok, InlineNamePolicy.validate(InlineNameKind.NEW_FILE, "Client.php", emptySet()))
        assertEquals(NameVerdict.Ok, InlineNamePolicy.validate(InlineNameKind.NEW_DIRECTORY, "Http", emptySet()))
    }

    fun testSurroundingWhitespaceIsIgnored() {
        assertEquals(NameVerdict.Ok, InlineNamePolicy.validate(InlineNameKind.NEW_FILE, "  Client.php  ", emptySet()))
    }

    fun testNestedPathIsAcceptedForCreation() {
        assertEquals(
            NameVerdict.Ok,
            InlineNamePolicy.validate(InlineNameKind.NEW_FILE, "src/Http/Client.php", emptySet()),
        )
    }

    fun testRenameAndDuplicateRefuseASlash() {
        for (kind in listOf(InlineNameKind.RENAME, InlineNameKind.DUPLICATE)) {
            for (typed in listOf("src/Client.php", "src\\Client.php")) {
                assertEquals(
                    InlineNamePolicy.ERROR_PATH_NOT_ALLOWED,
                    invalid(InlineNamePolicy.validate(kind, typed, emptySet())).messageKey,
                )
            }
        }
    }

    fun testOnlyCreationTakesAPath() {
        assertTrue(InlineNameKind.NEW_FILE.allowsPath)
        assertTrue(InlineNameKind.NEW_DIRECTORY.allowsPath)
        assertFalse(InlineNameKind.RENAME.allowsPath)
        assertFalse(InlineNameKind.DUPLICATE.allowsPath)
    }

    fun testEmptySegmentIsRejected() {
        assertEquals(
            InlineNamePolicy.ERROR_EMPTY_SEGMENT,
            invalid(InlineNamePolicy.validate(InlineNameKind.NEW_FILE, "src//Client.php", emptySet())).messageKey,
        )
    }

    fun testDotSegmentsAreRejected() {
        val verdict = invalid(InlineNamePolicy.validate(InlineNameKind.NEW_FILE, "../Client.php", emptySet()))
        assertEquals(InlineNamePolicy.ERROR_DOT_SEGMENT, verdict.messageKey)
        assertEquals("..", verdict.arg)
        assertEquals(
            InlineNamePolicy.ERROR_DOT_SEGMENT,
            invalid(InlineNamePolicy.validate(InlineNameKind.NEW_FILE, "./Client.php", emptySet())).messageKey,
        )
    }

    fun testIllegalCharactersAreRejectedAndNamed() {
        val verdict = invalid(InlineNamePolicy.validate(InlineNameKind.NEW_FILE, "src/a:b/Client.php", emptySet()))
        assertEquals(InlineNamePolicy.ERROR_INVALID_NAME, verdict.messageKey)
        assertEquals("a:b", verdict.arg)
    }

    fun testExistingSiblingIsRejected() {
        val verdict = invalid(
            InlineNamePolicy.validate(InlineNameKind.NEW_FILE, "Client.php", setOf("Client.php", "Server.php")),
        )
        assertEquals(InlineNamePolicy.ERROR_EXISTS, verdict.messageKey)
        assertEquals("Client.php", verdict.arg)
    }

    fun testCollisionIsNotCheckedOnceThePathHasFolders() {
        // What already sits inside `src/Http` is not something the field can know; creation reports that.
        assertEquals(
            NameVerdict.Ok,
            InlineNamePolicy.validate(InlineNameKind.NEW_FILE, "src/Http/Client.php", setOf("Client.php")),
        )
    }

    fun testDotFileIsAValidName() {
        assertEquals(NameVerdict.Ok, InlineNamePolicy.validate(InlineNameKind.NEW_FILE, ".gitignore", emptySet()))
    }

    // --- split ----------------------------------------------------------------------------------------

    fun testSplitOfAPlainName() {
        val path = InlineNamePolicy.split("Client.php")
        assertEquals(emptyList<String>(), path.parents)
        assertEquals("Client.php", path.name)
        assertFalse(path.trailingSeparator)
    }

    fun testSplitOfANestedPath() {
        val path = InlineNamePolicy.split("src/Http/Client.php")
        assertEquals(listOf("src", "Http"), path.parents)
        assertEquals("Client.php", path.name)
        assertFalse(path.trailingSeparator)
    }

    fun testTrailingSeparatorIsRecordedNotKept() {
        val path = InlineNamePolicy.split("src/Http/")
        assertEquals(listOf("src"), path.parents)
        assertEquals("Http", path.name)
        assertTrue(path.trailingSeparator)
    }

    fun testBackslashSplitsToo() {
        val path = InlineNamePolicy.split("src\\Http\\Client.php")
        assertEquals(listOf("src", "Http"), path.parents)
        assertEquals("Client.php", path.name)
    }

    // --- selectionEnd ---------------------------------------------------------------------------------

    fun testSelectionStopsBeforeTheExtension() {
        assertEquals("Client".length, InlineNamePolicy.selectionEnd("Client.php", isDirectory = false))
    }

    fun testSelectionStopsAtTheLastDot() {
        assertEquals("archive.tar".length, InlineNamePolicy.selectionEnd("archive.tar.gz", isDirectory = false))
    }

    fun testDotFileIsSelectedWhole() {
        assertEquals(".gitignore".length, InlineNamePolicy.selectionEnd(".gitignore", isDirectory = false))
    }

    fun testNameWithoutAnExtensionIsSelectedWhole() {
        assertEquals("LICENSE".length, InlineNamePolicy.selectionEnd("LICENSE", isDirectory = false))
    }

    fun testDirectoryIsSelectedWholeEvenWithADot() {
        assertEquals("some.dir".length, InlineNamePolicy.selectionEnd("some.dir", isDirectory = true))
    }

    // --- flagging -------------------------------------------------------------------------------------

    fun testAnEmptyFieldIsNotFlagged() {
        assertFalse(InlineNamePolicy.flags(InlineNamePolicy.validate(InlineNameKind.NEW_FILE, "", emptySet())))
    }

    fun testARealProblemIsFlagged() {
        assertTrue(InlineNamePolicy.flags(InlineNamePolicy.validate(InlineNameKind.NEW_FILE, "a:b", emptySet())))
        assertTrue(InlineNamePolicy.flags(InlineNamePolicy.validate(InlineNameKind.NEW_FILE, "A.md", setOf("A.md"))))
    }

    fun testAUsableNameIsNotFlagged() {
        assertFalse(InlineNamePolicy.flags(InlineNamePolicy.validate(InlineNameKind.NEW_FILE, "A.md", emptySet())))
    }
}
