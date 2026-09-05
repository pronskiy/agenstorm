package com.pronskiy.agenstorm.links

import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import java.awt.datatransfer.DataFlavor

/** Step A2.4: the editor popup action puts `relpath:line[:col]` on the clipboard, plus a Markdown link flavor. */
class CopyLocationLinkActionTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testCaretPositionRelativeToTheContentRoot() {
        open("src/Foo.php", "<?php\n\n\$a = 1;\n    \$b = 2;\n")
        myFixture.editor.caretModel.moveToLogicalPosition(LogicalPosition(3, 10))

        myFixture.performEditorAction(ACTION_ID)

        assertEquals("src/Foo.php:4:11", clipboard(DataFlavor.stringFlavor))
        assertEquals("[Foo.php:4](src/Foo.php:4:11)", clipboard(CopyLocationLinkAction.MARKDOWN_FLAVOR))
    }

    fun testColumnOneIsOmitted() {
        open("src/Foo.php", "<?php\n\n\$a = 1;\n")
        myFixture.editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 0))

        myFixture.performEditorAction(ACTION_ID)

        assertEquals("src/Foo.php:3", clipboard(DataFlavor.stringFlavor))
        assertEquals("[Foo.php:3](src/Foo.php:3)", clipboard(CopyLocationLinkAction.MARKDOWN_FLAVOR))
    }

    fun testSelectionUsesItsStart() {
        val text = "<?php\n\n\$a = 1;\n    \$b = 2;\n"
        open("src/Foo.php", text)
        val start = text.indexOf("1;")
        myFixture.editor.caretModel.moveToOffset(text.indexOf("2;"))
        myFixture.editor.selectionModel.setSelection(start, text.indexOf("2;"))

        myFixture.performEditorAction(ACTION_ID)

        assertEquals("src/Foo.php:3:6", clipboard(DataFlavor.stringFlavor))
    }

    fun testFileAtTheContentRoot() {
        open("notes.md", "# Notes\n\ntext\n")
        myFixture.editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 0))

        myFixture.performEditorAction(ACTION_ID)

        assertEquals("notes.md:3", clipboard(DataFlavor.stringFlavor))
    }

    fun testActionIsHiddenWhenTheFeatureIsOff() {
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State(linksEnabled = false))
        open("src/Foo.php", "<?php\n")

        val presentation = myFixture.testAction(CopyLocationLinkAction())

        assertFalse(presentation.isEnabledAndVisible)
    }

    private fun open(path: String, text: String) {
        val file = myFixture.addFileToProject(path, text)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
    }

    private fun clipboard(flavor: DataFlavor): String =
        CopyPasteManager.getInstance().contents!!.getTransferData(flavor) as String

    private companion object {
        const val ACTION_ID = "Agenstorm.CopyLocationLink"
    }
}
