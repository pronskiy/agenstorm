package com.pronskiy.agenstorm.projectview

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step O1.5. The slot round trip for the three ids the feature borrows. Whether the row actually appears is an
 * Epic O guardrail — there is no project view pane in a fixture to put one on.
 */
class ProjectViewActionInstallerTest : BasePlatformTestCase() {

    private val ids = listOf(
        ProjectViewActionInstaller.NEW_FILE_ACTION_ID,
        ProjectViewActionInstaller.NEW_DIR_ACTION_ID,
        ProjectViewActionInstaller.RENAME_ACTION_ID,
    )

    override fun setUp() {
        super.setUp()
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
    }

    override fun tearDown() {
        try {
            ProjectViewActionInstaller.uninstall()
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private fun actions() = ids.map { ActionManager.getInstance().getAction(it) }

    fun testTheThreePlatformActionsExistInThisIde() {
        for ((id, action) in ids.zip(actions())) {
            assertNotNull("$id is missing from this IDE", action)
        }
    }

    fun testTakingAndGivingBackTheSlots() {
        val stock = actions()

        ProjectViewActionInstaller.install()

        assertTrue(ProjectViewActionInstaller.isInstalled())
        assertTrue("the slots must hold ours", actions().zip(stock).none { it.first === it.second })
        assertTrue("every slot holds one of ours", actions().all { it is InlineCreateAction || it is InlineRenameAction })

        ProjectViewActionInstaller.uninstall()

        assertFalse(ProjectViewActionInstaller.isInstalled())
        assertEquals("every slot must hold exactly what was taken out of it", stock, actions())
    }

    fun testSyncFollowsTheSetting() {
        val stock = actions()

        AgenstormSettings.getInstance().state.projectTreeInlineNamingEnabled = false
        ProjectViewActionInstaller.sync()
        assertFalse(ProjectViewActionInstaller.isInstalled())
        assertEquals(stock, actions())

        AgenstormSettings.getInstance().state.projectTreeInlineNamingEnabled = true
        ProjectViewActionInstaller.sync()
        assertTrue(ProjectViewActionInstaller.isInstalled())

        AgenstormSettings.getInstance().state.projectTreeInlineNamingEnabled = false
        ProjectViewActionInstaller.sync()
        assertEquals(stock, actions())
    }

    fun testTakingTwiceChangesNothing() {
        ProjectViewActionInstaller.install()
        val ours = actions()

        ProjectViewActionInstaller.install()

        assertEquals("a second take must not displace our own actions", ours, actions())
    }

    fun testTheWrappersSurviveAMissingPlatformAction() {
        // A slot whose id this IDE does not have: the action is built with a null delegate and must not throw.
        val create = InlineCreateAction(InlineNameKind.NEW_FILE, null)
        val rename = InlineRenameAction(null)

        val createEvent = com.intellij.testFramework.TestActionEvent.createTestEvent(create)
        val renameEvent = com.intellij.testFramework.TestActionEvent.createTestEvent(rename)
        create.update(createEvent)
        rename.update(renameEvent)

        assertFalse(createEvent.presentation.isEnabledAndVisible)
        assertFalse(renameEvent.presentation.isEnabledAndVisible)
    }
}
