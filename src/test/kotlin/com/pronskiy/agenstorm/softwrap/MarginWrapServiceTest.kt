package com.pronskiy.agenstorm.softwrap

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.impl.SoftWrapModelImpl
import com.intellij.openapi.editor.impl.softwrap.mapping.SoftWrapApplianceManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Steps V1.2 and V1.4: a main editor of a file on the soft-wrap list is capped as it is created (the registered
 * `editorFactoryListener`), what the cap caps, other editors are left alone, and the off switch gives the width back.
 */
class MarginWrapServiceTest : BasePlatformTestCase() {

    private val editors = mutableListOf<Editor>()

    override fun tearDown() {
        try {
            editors.forEach { EditorFactory.getInstance().releaseEditor(it) }
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private fun editor(name: String, kind: EditorKind = EditorKind.MAIN_EDITOR): Editor {
        val file = myFixture.configureByText(name, "text").virtualFile
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        return EditorFactory.getInstance().createEditor(document, project, file, false, kind).also { editors += it }
    }

    private fun Editor.provider(): SoftWrapApplianceManager.VisibleAreaWidthProvider =
        ((this as EditorEx).softWrapModel as SoftWrapModelImpl).applianceManager.widthProvider

    fun testOnByDefault() {
        assertTrue(AgenstormSettings.State().softWrapAtRightMargin)
    }

    fun testTheProviderCapsTheEditorsOwnWidthAtTheMargin() {
        val editor = editor("notes.md")
        val margin = editor.settings.getRightMargin(project)
        val space = EditorUtil.getPlainSpaceWidth(editor)

        assertEquals(margin * space, MarginWidthProvider(editor) { 100_000 }.visibleAreaWidth)
        assertEquals("a narrower window wins", 300, MarginWidthProvider(editor) { 300 }.visibleAreaWidth)
    }

    fun testAMarkdownEditorIsCappedAsItIsCreatedAndGivenItsOwnWidthBackWhenSwitchedOff() {
        val editor = editor("notes.md")
        val capped = editor.provider() as MarginWidthProvider

        MarginWrapService.getInstance().attach(editor)
        assertSame("capped once", capped, editor.provider())

        AgenstormSettings.getInstance().state.softWrapAtRightMargin = false
        MarginWrapService.getInstance().applyToOpenEditors()
        assertSame(capped.original, editor.provider())

        AgenstormSettings.getInstance().state.softWrapAtRightMargin = true
        MarginWrapService.getInstance().applyToOpenEditors()
        assertTrue("on again", editor.provider() is MarginWidthProvider)
    }

    fun testAFileOffTheListADiffAndAConsoleKeepTheirOwnWidth() {
        assertFalse(editor("Login.php").provider() is MarginWidthProvider)
        assertFalse(editor("left.md", EditorKind.DIFF).provider() is MarginWidthProvider)
        assertFalse(editor("out.txt", EditorKind.CONSOLE).provider() is MarginWidthProvider)
    }

    fun testSwitchedOffANewEditorIsNotCapped() {
        AgenstormSettings.getInstance().state.softWrapAtRightMargin = false
        assertFalse(editor("notes.md").provider() is MarginWidthProvider)
    }
}
