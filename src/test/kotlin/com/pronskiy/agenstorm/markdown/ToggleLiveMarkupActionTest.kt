package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/** Step F2.4: the toggle is registered, shows only for Markdown editors, and flips one editor independently of the setting. */
class ToggleLiveMarkupActionTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testActionIsRegisteredWithoutAShortcut() {
        val action = ActionManager.getInstance().getAction("Agenstorm.ToggleLiveMarkup")
        assertTrue(action is ToggleLiveMarkupAction)
        assertTrue(KeymapManager.getInstance().activeKeymap.getShortcuts("Agenstorm.ToggleLiveMarkup").isEmpty())
        assertEquals("Live Markup", action.templatePresentation.text)
    }

    fun testShownAndSelectedForAMarkdownEditorOnly() {
        myFixture.configureByText("a.md", "**b**\n")
        val action = ToggleLiveMarkupAction()
        val event = TestActionEvent.createTestEvent(action, context())
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
        assertTrue(action.isSelected(event))

        myFixture.configureByText("a.txt", "**b**\n")
        val textEvent = TestActionEvent.createTestEvent(action, context())
        action.update(textEvent)
        assertFalse(textEvent.presentation.isVisible)
    }

    fun testTogglingOffRemovesRegionsAndBackOnRestoresThem() {
        myFixture.configureByText("a.md", "**b** and *i*\n")
        val service = LiveMarkupService.getInstance(project)
        val action = ToggleLiveMarkupAction()
        val event = TestActionEvent.createTestEvent(action, context())
        service.controllerFor(myFixture.editor)!!.syncNow()
        assertEquals(4, service.controllerFor(myFixture.editor)!!.regions().size)

        action.setSelected(event, false)
        assertNull(service.controllerFor(myFixture.editor))
        assertEmpty(myFixture.editor.foldingModel.allFoldRegions.filter { it.getUserData(LiveMarkupController.KIND) != null })
        assertEquals(false, myFixture.editor.getUserData(LiveMarkupService.ENABLED_OVERRIDE))
        assertFalse(action.isSelected(event))

        action.setSelected(event, true)
        val controller = service.controllerFor(myFixture.editor)!!
        controller.syncNow()
        assertEquals(4, controller.regions().size)
        assertTrue(action.isSelected(event))
    }

    fun testEditorChoiceWinsOverTheGlobalSetting() {
        myFixture.configureByText("a.md", "**b**\n")
        val service = LiveMarkupService.getInstance(project)
        val action = ToggleLiveMarkupAction()
        val event = TestActionEvent.createTestEvent(action, context())

        AgenstormSettings.getInstance().state.liveMarkupEnabled = false
        service.applySettings()
        assertNull("global off detaches an editor without its own choice", service.controllerFor(myFixture.editor))

        action.setSelected(event, true)
        assertNotNull(service.controllerFor(myFixture.editor))
        service.applySettings()
        assertNotNull("the editor's own choice survives a settings apply", service.controllerFor(myFixture.editor))

        AgenstormSettings.getInstance().state.liveMarkupEnabled = true
        action.setSelected(event, false)
        service.applySettings()
        assertNull("…in both directions", service.controllerFor(myFixture.editor))
    }

    fun testCheckboxOptionOffLeavesTaskBoxesRaw() {
        myFixture.configureByText("a.md", "- [ ] task **b**\n")
        val service = LiveMarkupService.getInstance(project)
        val controller = service.controllerFor(myFixture.editor)!!
        controller.syncNow()
        assertEquals(setOf(MarkupKind.BULLET, MarkupKind.CHECKBOX_OFF, MarkupKind.STRONG), controller.regions().map { it.getUserData(LiveMarkupController.KIND) }.toSet())

        AgenstormSettings.getInstance().state.liveMarkupCheckboxes = false
        service.applySettings()
        controller.syncNow()
        assertEquals(setOf(MarkupKind.BULLET, MarkupKind.STRONG), controller.regions().map { it.getUserData(LiveMarkupController.KIND) }.toSet())

        AgenstormSettings.getInstance().state.liveMarkupBullets = false
        service.applySettings()
        controller.syncNow()
        assertEquals(setOf(MarkupKind.STRONG), controller.regions().map { it.getUserData(LiveMarkupController.KIND) }.toSet())
    }

    private fun context() = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(CommonDataKeys.EDITOR, myFixture.editor)
        .add(CommonDataKeys.VIRTUAL_FILE, myFixture.file.virtualFile)
        .build()
}
