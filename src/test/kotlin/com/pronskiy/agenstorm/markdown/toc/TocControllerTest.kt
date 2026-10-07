package com.pronskiy.agenstorm.markdown.toc

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/** Steps W2.2 and W2.4: what one editor's controller lists, when it shows, how it lives and dies, and where a click lands. */
class TocControllerTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private fun controllerFor(text: String): TocController {
        myFixture.configureByText("a.md", text)
        val controller = TocService.getInstance(project).attach(myFixture.editor as EditorEx, toolbar = null, parent = testRootDisposable)
        controller.collectNow()
        return controller
    }

    fun testListsHeadingsUpToTheDepth() {
        val controller = controllerFor("# A\n\n## B\n\n### C\n\n#### D\n")
        assertEquals(listOf("A", "B", "C"), controller.state.visible.map { it.title })
        assertTrue(controller.state.shows)

        AgenstormSettings.getInstance().state.markdownTocDepth = 2
        controller.refresh()
        assertEquals(listOf("A", "B"), controller.state.visible.map { it.title })
    }

    fun testHiddenBelowTwoHeadingsAndWhenSwitchedOff() {
        assertFalse(controllerFor("# Only\n\ntext\n").state.shows)

        val controller = controllerFor("# One\n\n## Two\n")
        assertTrue(controller.state.shows)
        AgenstormSettings.getInstance().state.markdownTocEnabled = false
        controller.refresh()
        assertFalse(controller.state.shows)
    }

    fun testHeadingsOnlyInCodeAndFrontMatterShowNothing() {
        val controller = controllerFor("---\ntitle: x\n---\n\n```\n# a\n# b\n```\n")
        assertTrue(controller.state.visible.isEmpty())
        assertFalse(controller.state.shows)
    }

    fun testNavigateMovesTheCaretToTheHeading() {
        val controller = controllerFor("# One\n\ntext\n\n## Two\n\nmore\n")
        val two = controller.state.visible[1]
        myFixture.editor.selectionModel.setSelection(0, 3)
        controller.navigate(two)
        assertEquals(two.offset, myFixture.editor.caretModel.offset)
        assertFalse(myFixture.editor.selectionModel.hasSelection())
    }

    fun testTheServiceDropsTheControllerWithItsToolbar() {
        myFixture.configureByText("a.md", "# A\n\n## B\n")
        val toolbarLifetime = Disposer.newDisposable(testRootDisposable, "toolbar")
        val service = TocService.getInstance(project)
        val controller = service.attach(myFixture.editor as EditorEx, toolbar = null, parent = toolbarLifetime)
        assertSame(controller, service.controllerFor(myFixture.editor))
        Disposer.dispose(toolbarLifetime)
        assertNull(service.controllerFor(myFixture.editor))
    }

    fun testNothingIsNotifiedAfterDispose() {
        myFixture.configureByText("a.md", "# A\n\n## B\n")
        val toolbarLifetime = Disposer.newDisposable(testRootDisposable, "toolbar")
        val controller = TocService.getInstance(project).attach(myFixture.editor as EditorEx, toolbar = null, parent = toolbarLifetime)
        var calls = 0
        controller.subscribe { calls++ }
        controller.refresh()
        assertEquals(1, calls)
        Disposer.dispose(toolbarLifetime)
        controller.refresh()
        controller.collectNow()
        assertEquals(1, calls)
    }
}
