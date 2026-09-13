package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step M2.1. The slot round trip and the one invariant that would break every split editor in the IDE if it were
 * wrong: `TextEditorWithPreview.getShowEditorAction()` does `ActionUtil.getAction(id) as ToggleAction` with a hard
 * null check, so whatever sits in these three slots must be a `ToggleAction`.
 *
 * Whether the buttons actually disappear is an Epic M guardrail — it depends on `isShowActionsInTabs()`, hence on the
 * IDE's tab placement, and there is no editor tab to look at here.
 */
class LayoutSwitcherActionsTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            LayoutSwitcherInstaller.uninstall()
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private fun actions() = LayoutSwitcherInstaller.ACTION_IDS.map { ActionManager.getInstance().getAction(it) }

    fun testTheThreePlatformActionsExistAndAreToggleActions() {
        val actions = actions()

        assertEquals(3, actions.size)
        for ((id, action) in LayoutSwitcherInstaller.ACTION_IDS.zip(actions)) {
            assertNotNull("$id is missing from this IDE", action)
            assertTrue("$id must be a ToggleAction; TextEditorWithPreview casts it", action is ToggleAction)
        }
    }

    fun testTakingAndGivingBackTheSlots() {
        val stock = actions()

        AgenstormSettings.getInstance().state.markdownHideLayoutSwitcher = true
        LayoutSwitcherInstaller.sync()

        assertTrue(LayoutSwitcherInstaller.isInstalled())
        for (action in actions()) assertTrue("ours must still be a ToggleAction", action is ToggleAction)
        assertTrue("the slots must hold ours, not the platform's", actions().zip(stock).none { it.first === it.second })

        AgenstormSettings.getInstance().state.markdownHideLayoutSwitcher = false
        LayoutSwitcherInstaller.sync()

        assertFalse(LayoutSwitcherInstaller.isInstalled())
        assertEquals("every slot must hold exactly what was taken out of it", stock, actions())
    }

    fun testTheFeatureOffLeavesThePlatformActionsAlone() {
        val stock = actions()

        LayoutSwitcherInstaller.sync()

        assertEquals(stock, actions())
        assertFalse(LayoutSwitcherInstaller.isInstalled())
    }

    fun testTheWrapperSurvivesAMissingPlatformAction() {
        val action = HiddenInMarkdownToggleAction(null)

        assertFalse(action.isSelected(com.intellij.testFramework.TestActionEvent.createTestEvent(action)))
    }

    fun testANonMarkdownFileIsNotHidden() {
        val php = myFixture.configureByText("Foo.php", "<?php").virtualFile
        val md = myFixture.configureByText("Notes.md", "# hi").virtualFile

        assertFalse(HiddenInMarkdownToggleAction.isMarkdown(php))
        assertTrue(HiddenInMarkdownToggleAction.isMarkdown(md))
        assertFalse(HiddenInMarkdownToggleAction.isMarkdown(null))
    }
}
