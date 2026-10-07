package com.pronskiy.agenstorm.markdown.toc

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/** Step W2.5: the action shows exactly while its editor's controller says so and hands that controller to the panel. */
class TocActionTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private fun context() = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(CommonDataKeys.EDITOR, myFixture.editor)
        .build()

    fun testHiddenWithoutAController() {
        myFixture.configureByText("a.md", "# A\n\n## B\n")
        val action = TocAction()
        val event = TestActionEvent.createTestEvent(action, context())
        action.update(event)
        assertFalse(event.presentation.isVisible)
    }

    fun testVisibleWhileTheControllerShowsAndHiddenWhenOff() {
        myFixture.configureByText("a.md", "# A\n\n## B\n")
        val controller = TocService.getInstance(project).attach(myFixture.editor as EditorEx, toolbar = null, parent = testRootDisposable)
        controller.collectNow()
        val action = TocAction()

        val shown = TestActionEvent.createTestEvent(action, context())
        action.update(shown)
        assertTrue(shown.presentation.isEnabledAndVisible)
        assertSame(controller, shown.presentation.getClientProperty(TocAction.CONTROLLER))

        AgenstormSettings.getInstance().state.markdownTocEnabled = false
        controller.refresh()
        val hidden = TestActionEvent.createTestEvent(action, context())
        action.update(hidden)
        assertFalse(hidden.presentation.isVisible)
    }

    fun testTheCustomComponentIsThePanel() {
        assertTrue(TocAction().createCustomComponent(TocAction().templatePresentation.clone(), "ContextToolbar") is TocPanel)
    }
}
