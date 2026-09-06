package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.paths.WebReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination

/**
 * Step F2.3: Go to Declaration from inside inline-link text lands where the hidden destination points — a file, a
 * `path:line:col` location, a heading — and a URL destination yields a browser-opening target. Reference-style links
 * and the feature toggle are left alone.
 */
class LinkTextGotoDeclarationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("src/Foo.php", "<?php\n\n\$a = 1;\n    \$b = 2;\n")
        myFixture.addFileToProject("docs/other.md", "# Other\n\ntext\n")
    }

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testFileLinkOpensTheFileFromItsVisibleText() {
        myFixture.configureByText("plan.md", "see [the ot<caret>her doc](docs/other.md) now\n")
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        assertEquals("docs/other.md", selectedPath())
    }

    fun testLocationLinkOpensTheFileAtLineAndColumn() {
        myFixture.configureByText("plan.md", "fix [th<caret>is](src/Foo.php:3:5)\n")
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        assertEquals("src/Foo.php", selectedPath())
        assertEquals(LogicalPosition(2, 4), FileEditorManager.getInstance(project).selectedTextEditor!!.caretModel.logicalPosition)
    }

    fun testMarkupInsideTheLinkTextStillNavigates() {
        myFixture.configureByText("plan.md", "see [**bo<caret>ld** link](docs/other.md)\n")
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        assertEquals("docs/other.md", selectedPath())
    }

    fun testHeadingLinkMovesToTheHeading() {
        myFixture.configureByText("plan.md", "# Intro\n\ntext, see [in<caret>tro](#intro)\n")
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        assertEquals("plan.md", selectedPath())
        assertEquals(0, myFixture.editor.caretModel.logicalPosition.line)
    }

    fun testHeadingLinkIntoAnotherFileOpensItAtTheHeading() {
        myFixture.addFileToProject("docs/long.md", "intro\n\n# First\n\n## Second part\n\ntext\n")
        myFixture.configureByText("plan.md", "see [sec<caret>ond](docs/long.md#second-part)\n")
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        assertEquals("docs/long.md", selectedPath())
        assertEquals(4, FileEditorManager.getInstance(project).selectedTextEditor!!.caretModel.logicalPosition.line)

        myFixture.configureByText("plan2.md", "see [no<caret>ne](docs/long.md#nowhere)\n")
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        assertEquals("a missing heading still opens the file", "docs/long.md", selectedPath())
    }

    fun testUrlLinkYieldsABrowserTarget() {
        myFixture.configureByText("plan.md", "see [site](https://example.com/x) now\n")
        val destination = PsiTreeUtil.findChildOfType(myFixture.file, MarkdownLinkDestination::class.java)!!
        val target = LinkTextGotoDeclarationHandler.targetsOf(destination).single()
        assertEquals(WebReference::class.java, target.javaClass.enclosingClass)
        assertTrue(target.isValid)
    }

    fun testReferenceLinksAndPlainTextGetNoTargets() {
        myFixture.configureByText("plan.md", "see [re<caret>f][r] and plain\n\n[r]: docs/other.md\n")
        assertNull(targetsAtCaret())
        myFixture.configureByText("b.md", "pla<caret>in words\n")
        assertNull(targetsAtCaret())
    }

    fun testFeatureToggleOffLeavesTheHandlerSilent() {
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State(liveMarkupEnabled = false))
        myFixture.configureByText("plan.md", "see [the ot<caret>her doc](docs/other.md) now\n")
        assertNull(targetsAtCaret())
    }

    fun testMissingFileGivesNoTarget() {
        myFixture.configureByText("plan.md", "see [go<caret>ne](docs/missing.md)\n")
        assertNull(targetsAtCaret())
    }

    private fun targetsAtCaret(): Array<out Any>? {
        val offset = myFixture.caretOffset
        val element = myFixture.file.findElementAt(offset)
        return LinkTextGotoDeclarationHandler().getGotoDeclarationTargets(element, offset, myFixture.editor)
    }

    private fun selectedPath(): String {
        val root = myFixture.tempDirFixture.getFile("")!!.path
        return FileEditorManager.getInstance(project).selectedFiles.single().path.removePrefix("$root/")
    }
}
