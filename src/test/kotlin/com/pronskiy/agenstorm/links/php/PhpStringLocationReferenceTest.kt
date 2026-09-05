package com.pronskiy.agenstorm.links.php

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReferenceService
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.links.FileLocation
import com.pronskiy.agenstorm.links.FileLocationPsiReference
import com.pronskiy.agenstorm.links.FileLocationTarget

/** Steps A2.3/A2.5: `path:line[:col]` tokens inside PHP string literals (single, double quoted, heredoc). */
class PhpStringLocationReferenceTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("src/Foo.php", "<?php\n\n\$a = 1;\n    \$b = 2;\n")
    }

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testSingleQuotedString() {
        val file = myFixture.configureByText("a.php", "<?php\n\$x = 'src/Foo.php:3';\n")
        val reference = ourReferences(file).single()
        assertEquals("src/Foo.php:3", reference.rangeInElement.substring(literal(file).text))
        assertTarget(reference, 3, null)
    }

    fun testDoubleQuotedStringWithSurroundingText() {
        val file = myFixture.configureByText("a.php", "<?php\n\$x = \"see src/Foo.php:3:5 for details\";\n")
        assertTarget(ourReferences(file).single(), 3, 5)
    }

    fun testHeredoc() {
        val file = myFixture.configureByText("a.php", "<?php\n\$x = <<<TXT\nFixed in src/Foo.php:3:5.\nTXT;\n")
        assertTarget(ourReferences(file).single(), 3, 5)
    }

    fun testHugeStringIsSkipped() {
        val file = myFixture.configureByText("a.php", "<?php\n\$x = '" + "x".repeat(20_001) + " src/Foo.php:3';\n")
        assertEmpty(ourReferences(file))
    }

    fun testUrlAndPlainStringsYieldNothing() {
        val file = myFixture.configureByText("a.php", "<?php\n\$x = 'http://localhost:8080/index.php:80';\n\$y = 'App\\\\Foo::bar';\n")
        assertEmpty(ourReferences(file))
    }

    fun testLocationInStringIsHighlightedAndNavigable() {
        myFixture.configureByText("a.php", "<?php\n\$x = 'see src/F<caret>oo.php:3:5';\n")
        val highlighted = myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey == DefaultLanguageHighlighterColors.HIGHLIGHTED_REFERENCE }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset) }
        assertEquals(listOf("src/Foo.php:3:5"), highlighted)

        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        val manager = FileEditorManager.getInstance(project)
        assertEquals(listOf(myFixture.findFileInTempDir("src/Foo.php")), manager.selectedFiles.toList())
        assertEquals(LogicalPosition(2, 4), manager.selectedTextEditor!!.caretModel.logicalPosition)
    }

    fun testFeatureToggleOffDisablesTheContributor() {
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State(linksEnabled = false))
        val file = myFixture.configureByText("a.php", "<?php\n\$x = 'src/Foo.php:3';\n")
        assertEmpty(ourReferences(file))
    }

    private fun literal(file: PsiFile): StringLiteralExpression =
        PsiTreeUtil.findChildrenOfType(file, StringLiteralExpression::class.java).single()

    private fun ourReferences(file: PsiFile): List<FileLocationPsiReference> =
        PsiTreeUtil.findChildrenOfType(file, StringLiteralExpression::class.java).flatMap { literal ->
            PsiReferenceService.getService().getReferences(literal, PsiReferenceService.Hints.NO_HINTS).filterIsInstance<FileLocationPsiReference>()
        }

    private fun assertTarget(reference: FileLocationPsiReference, line: Int, column: Int?) {
        val target = reference.resolve() as FileLocationTarget
        assertEquals(myFixture.findFileInTempDir("src/Foo.php"), target.file)
        assertEquals(FileLocation("src/Foo.php", line, column), target.location)
        assertTrue(reference.isHighlightedWhenSoft)
    }
}
