package com.pronskiy.agenstorm.projectview

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Step O3.1: which New menu entries the feature takes. The scan is by name, so what it must get right is the
 * line between an entry that is only a name and one that is not.
 */
class TemplateEntryScanTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            ProjectViewActionInstaller.uninstall()
        } finally {
            super.tearDown()
        }
    }

    fun testTheTypedFileEntriesAreFound() {
        val found = ProjectViewActionInstaller.scanForTests()

        assertFalse("this IDE should offer some '... File' entries: $found", found.isEmpty())
        for ((id, name) in found) {
            assertTrue("$id -> '$name' should end in ' File'", name.endsWith(" File"))
        }
    }

    fun testTheFeaturesOwnEntriesAreNotTakenTwice() {
        val found = ProjectViewActionInstaller.scanForTests()

        assertFalse(found.containsKey(ProjectViewActionInstaller.NEW_FILE_ACTION_ID))
        assertFalse(found.containsKey(ProjectViewActionInstaller.NEW_DIR_ACTION_ID))
        assertFalse("the scratch popup is Epic B's", found.containsKey("NewScratchFile"))
    }

    fun testEntriesThatAreMoreThanANameAreLeftAlone() {
        val found = ProjectViewActionInstaller.scanForTests()

        // PHP Class, Interface and Trait carry a namespace; nothing named without " File" is taken.
        for (name in found.values) {
            assertFalse("'$name' is not a plain file entry", name.endsWith(" Class"))
            assertFalse("'$name' is not a plain file entry", name.endsWith(" Interface"))
            assertFalse("'$name' is not a plain file entry", name.endsWith(" Trait"))
        }
    }

    fun testATemplateThatDoesNotResolveFallsBackInsteadOfThrowing() {
        val action = InlineTemplateFileAction("No Such Template File", null)
        val event = com.intellij.testFramework.TestActionEvent.createTestEvent(action)

        action.update(event)

        assertFalse("a null delegate means the entry is not there at all", event.presentation.isEnabledAndVisible)
    }
}
