package com.pronskiy.agenstorm.scratch

import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Steps B2.2/B2.3: the action holds the platform's `NewScratchFile` slot while the feature is on and hands it
 * back when it is off, and the scratch it creates is the platform's own kind of file.
 */
class NewScratchFileActionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        ScratchActionInstaller.uninstall()
    }

    override fun tearDown() {
        try {
            ScratchActionInstaller.uninstall()
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private fun scratchAction() =
        ActionManager.getInstance().getAction(ScratchActionInstaller.NEW_SCRATCH_FILE_ACTION_ID)

    fun testInstallTakesThePlatformSlotAndUninstallGivesItBack() {
        val stock = scratchAction()
        assertNotNull("this IDE has no NewScratchFile action", stock)
        assertFalse(stock is NewScratchFileAction)

        ScratchActionInstaller.install()
        assertTrue(scratchAction() is NewScratchFileAction)
        assertTrue(ScratchActionInstaller.isInstalled())

        // Idempotent: installing twice must not record our own action as the one to restore.
        ScratchActionInstaller.install()
        ScratchActionInstaller.uninstall()

        assertSame(stock, scratchAction())
        assertFalse(ScratchActionInstaller.isInstalled())
    }

    fun testSyncFollowsTheFeatureToggle() {
        ScratchActionInstaller.sync()
        assertTrue(scratchAction() is NewScratchFileAction)

        AgenstormSettings.getInstance().state.scratchFilterEnabled = false
        ScratchActionInstaller.sync()
        assertFalse(scratchAction() is NewScratchFileAction)

        AgenstormSettings.getInstance().state.scratchFilterEnabled = true
        ScratchActionInstaller.sync()
        assertTrue(scratchAction() is NewScratchFileAction)
    }

    /** Whatever the popup ends up choosing, the file has to land where the platform keeps scratches. */
    fun testCreatesARealScratchFileWithTheLanguageExtension() {
        val php = Language.findLanguageByID("PHP")!!

        val file = ScratchRootType.getInstance().createScratchFile(project, "scratch.php", php, "<?php\n")

        assertNotNull(file)
        assertEquals("php", file!!.extension)
        assertEquals(ScratchRootType.getInstance(), ScratchFileService.getInstance().getRootType(file))
        assertEquals("<?php\n", String(file.contentsToByteArray()))
    }

    /** Two scratches of the same language do not fight over the name; the platform picks the next free one. */
    fun testASecondScratchOfTheSameLanguageGetsItsOwnName() {
        val php = Language.findLanguageByID("PHP")!!

        val first = ScratchRootType.getInstance().createScratchFile(project, "scratch.php", php, "")
        val second = ScratchRootType.getInstance().createScratchFile(project, "scratch.php", php, "")

        assertNotNull(first)
        assertNotNull(second)
        assertFalse("expected different names, both are ${first!!.name}", first.name == second!!.name)
    }

    fun testTheActionCarriesItsOwnTextSinceReplaceActionDoesNotCopyOne() {
        val presentation = NewScratchFileAction().templatePresentation

        assertEquals("Scratch File", presentation.text)
        assertTrue(presentation.description!!.isNotBlank())
    }
}
