package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.markdown.tables.EditTableAsTextAction
import java.awt.Point

/**
 * Step Q3.4: **Edit Table as Text** in the editor context menu reveals the rendered table under the click — or,
 * invoked without a point, the first rendered table at or after the caret — by moving the caret into its first
 * cell, which the reveal rule answers with the raw table. Shown only where a rendered table exists.
 *
 * Same document as [TableFoldingTest]: the table region is (6,40), the header's first cell content `a` is at 9.
 */
class EditTableAsTextActionTest : BasePlatformTestCase() {

    private val text = "Above\n\n| a | b |\n|---|---|\n| **c** | d |\n\nBelow\n"

    fun testActionIsInTheMarkdownContextMenuWithoutAShortcut() {
        val action = ActionManager.getInstance().getAction("Agenstorm.EditTableAsText")
        assertNotNull(action)
        assertTrue(action is EditTableAsTextAction)
        assertEmpty(KeymapManager.getInstance().activeKeymap.getShortcuts("Agenstorm.EditTableAsText"))
        val group = ActionManager.getInstance().getAction("Markdown.EditorContextMenuGroup") as com.intellij.openapi.actionSystem.ActionGroup
        assertTrue(group.getChildren(null).any { it === action })
    }

    fun testShownOnlyWhereARenderedTableExists() {
        val controller = configured()
        val action = EditTableAsTextAction()
        val shown = TestActionEvent.createTestEvent(action, context())
        action.update(shown)
        assertTrue(shown.presentation.isEnabledAndVisible)

        controller.clickTable(pointOn(controller))
        controller.cellEditor().cancel()
        myFixture.editor.caretModel.moveToOffset(20)
        UIUtil.dispatchAllInvocationEvents()
        assertEmpty("the table is revealed, so nothing is rendered", controller.tableInlays())
        val hidden = TestActionEvent.createTestEvent(action, context())
        action.update(hidden)
        assertFalse(hidden.presentation.isEnabledAndVisible)

        myFixture.configureByText("plain.md", "no table here\n")
        val none = TestActionEvent.createTestEvent(action, context())
        action.update(none)
        assertFalse(none.presentation.isEnabledAndVisible)
    }

    fun testFromTheContextMenuTheTableUnderThePointIsRevealed() {
        val controller = configured()
        val action = EditTableAsTextAction()
        action.actionPerformed(TestActionEvent.createTestEvent(action, context(pointOn(controller))))
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(9, myFixture.editor.caretModel.offset)
        assertTrue(tableRegion(controller).isExpanded)
        assertEmpty(controller.tableInlays())
    }

    fun testWithoutAPointTheFirstRenderedTableAfterTheCaretIsRevealed() {
        val controller = configured()
        val action = EditTableAsTextAction()
        action.actionPerformed(TestActionEvent.createTestEvent(action, context()))
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(9, myFixture.editor.caretModel.offset)
        assertTrue(tableRegion(controller).isExpanded)
    }

    private fun configured(): LiveMarkupController {
        myFixture.configureByText("a.md", text)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val controller = LiveMarkupService.getInstance(project).controllerFor(myFixture.editor)
            ?: error("no live markup controller on the Markdown editor")
        controller.syncNow()
        return controller
    }

    private fun context(point: Point? = null): DataContext {
        val builder = SimpleDataContext.builder().add(CommonDataKeys.EDITOR, myFixture.editor).add(CommonDataKeys.PROJECT, project)
        if (point != null) builder.add(PlatformDataKeys.CONTEXT_MENU_POINT, point)
        return builder.build()
    }

    private fun pointOn(controller: LiveMarkupController): Point {
        val inlay = controller.tableInlays().single()
        val bounds = inlay.renderer.cellBounds(inlay, 1, 1) ?: error("no cell")
        return Point(bounds.x + 2, bounds.y + 2)
    }

    private fun tableRegion(controller: LiveMarkupController) =
        controller.regions().single { it.getUserData(LiveMarkupController.KIND) == MarkupKind.TABLE }
}
