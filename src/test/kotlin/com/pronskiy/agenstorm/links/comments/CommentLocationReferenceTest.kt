package com.pronskiy.agenstorm.links.comments

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReferenceService
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.links.FileLocation
import com.pronskiy.agenstorm.links.FileLocationPsiReference
import com.pronskiy.agenstorm.links.FileLocationTarget
import com.pronskiy.agenstorm.links.LocationGotoDeclarationHandler

/**
 * Steps A2.1/A2.5: every PsiComment in every language gets FileLocationPsiReferences through the
 * `commentsReferenceProvider` key: PHP line, block and PHPDoc comments, and an XML comment as the
 * "any other language" case.
 */
class CommentLocationReferenceTest : BasePlatformTestCase() {

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

    fun testPhpLineComment() {
        val file = myFixture.configureByText("a.php", "<?php\n// see src/Foo.php:3\n")
        val reference = singleReference(file)
        assertEquals("src/Foo.php:3", reference.rangeInElement.substring(comment(file).text))
        assertTarget(reference, "src/Foo.php", 3, null)
    }

    fun testPhpBlockComment() {
        val file = myFixture.configureByText("a.php", "<?php\n/* fixed in src/Foo.php:3:5 */\n")
        assertTarget(singleReference(file), "src/Foo.php", 3, 5)
    }

    fun testPhpDocComment() {
        val file = myFixture.configureByText("a.php", "<?php\n/**\n * @see src/Foo.php:3:5\n */\nfunction f() {}\n")
        val docComment = PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java).single { it.text.startsWith("/**") }
        // PhpDocCommentImpl is a ContributedReferenceHost whose own getReferences() ignores the registry;
        // highlighting and LocationGotoDeclarationHandler go through PsiReferenceService instead.
        assertEmpty(docComment.references)
        val reference = referencesOf(docComment).single()
        assertTarget(reference, "src/Foo.php", 3, 5)
    }

    fun testXmlComment() {
        val file = myFixture.configureByText("a.xml", "<!-- see src/Foo.php:3 --><root/>")
        assertTarget(singleReference(file), "src/Foo.php", 3, null)
    }

    fun testJavaScriptLineAndDocComments() {
        val file = myFixture.configureByText("a.js", "// see src/Foo.php:3\n/**\n * Fixed in src/Foo.php:3:5\n */\nfunction f() {}\n")
        val references = ourReferences(file)
        assertEquals(listOf(FileLocation("src/Foo.php", 3, null), FileLocation("src/Foo.php", 3, 5)), references.map { it.match.location })
        references.forEach { assertTarget(it, "src/Foo.php", it.match.location.line, it.match.location.column) }
    }

    fun testYamlComment() {
        val file = myFixture.configureByText("a.yaml", "# see src/Foo.php:3\nkey: value\n")
        assertTarget(singleReference(file), "src/Foo.php", 3, null)
    }

    fun testLargePhpFileWithManyCommentsHighlightsQuickly() {
        val lines = ArrayList<String>(5_100)
        lines += "<?php"
        for (i in 1..5_000) {
            lines += if (i % 25 == 0) "// see src/Foo.php:3:5 (comment $i)" else "\$v$i = $i;"
        }
        myFixture.configureByText("big.php", lines.joinToString("\n") + "\n")

        val started = System.nanoTime()
        val highlighted = myFixture.doHighlighting().count { it.forcedTextAttributesKey == DefaultLanguageHighlighterColors.HIGHLIGHTED_REFERENCE }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(200, highlighted)
        assertTrue("highlighting took ${'$'}elapsedMs ms", elapsedMs < 15_000)
    }

    fun testUrlWithPortAndPlainTextYieldNothing() {
        val file = myFixture.configureByText("a.php", "<?php\n// see http://x:80/index.php:80 and 10:20:30\n")
        assertEmpty(ourReferences(file))
    }

    fun testUnresolvedLocationIsASoftUnhighlightedReference() {
        val file = myFixture.configureByText("a.php", "<?php\n// see src/Foo.php:3 and missing.php:1\n")
        val references = ourReferences(file)
        assertEquals(listOf(FileLocation("src/Foo.php", 3, null), FileLocation("missing.php", 1, null)), references.map { it.match.location })
        assertNull(references[1].resolve())
        assertTrue(references[1].isSoft)

        val highlighted = myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey == DefaultLanguageHighlighterColors.HIGHLIGHTED_REFERENCE }
            .map { file.text.substring(it.startOffset, it.endOffset) }
        assertEquals(listOf("src/Foo.php:3"), highlighted)
    }

    fun testGotoDeclarationFromALineComment() {
        myFixture.configureByText("a.php", "<?php\n// see src/F<caret>oo.php:3:5\n")
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        assertNavigatedToFooAt(LogicalPosition(2, 4))
    }

    fun testGotoDeclarationFromAPhpDocDescription() {
        // PhpDocCommentImpl.getReferences() is empty, so this only works through LocationGotoDeclarationHandler.
        myFixture.configureByText("a.php", "<?php\n/**\n * Fixed in src/F<caret>oo.php:3:5 today.\n */\nfunction g() {}\n")
        myFixture.performEditorAction(IdeActions.ACTION_GOTO_DECLARATION)
        assertNavigatedToFooAt(LogicalPosition(2, 4))
    }

    fun testGotoDeclarationHandlerDoesNotDuplicateExposedReferences() {
        val file = myFixture.configureByText("a.php", "<?php\n// see src/F<caret>oo.php:3:5\n")
        val leaf = file.findElementAt(myFixture.caretOffset)!!
        assertNull(LocationGotoDeclarationHandler().getGotoDeclarationTargets(leaf, myFixture.caretOffset, myFixture.editor))

        val doc = myFixture.configureByText("b.php", "<?php\n/** see src/F<caret>oo.php:3 */\nfunction g() {}\n")
        val docLeaf = doc.findElementAt(myFixture.caretOffset)!!
        val targets = LocationGotoDeclarationHandler().getGotoDeclarationTargets(docLeaf, myFixture.caretOffset, myFixture.editor)!!
        assertEquals(FileLocation("src/Foo.php", 3, null), (targets.single() as FileLocationTarget).location)
    }

    fun testHugeCommentIsSkipped() {
        val file = myFixture.configureByText("a.php", "<?php\n/* " + "x".repeat(20_001) + " src/Foo.php:3 */\n")
        assertEmpty(ourReferences(file))
    }

    fun testFeatureToggleOffDisablesTheProvider() {
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State(linksEnabled = false))
        val file = myFixture.configureByText("a.php", "<?php\n// see src/Foo.php:3\n")
        assertEmpty(ourReferences(file))
    }

    private fun assertNavigatedToFooAt(position: LogicalPosition) {
        val manager = FileEditorManager.getInstance(project)
        assertEquals(listOf(myFixture.findFileInTempDir("src/Foo.php")), manager.selectedFiles.toList())
        assertEquals(position, manager.selectedTextEditor!!.caretModel.logicalPosition)
    }

    private fun comment(file: PsiFile): PsiComment = PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java).single()

    private fun ourReferences(file: PsiFile): List<FileLocationPsiReference> =
        PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java).flatMap(::referencesOf)

    private fun referencesOf(comment: PsiComment): List<FileLocationPsiReference> =
        PsiReferenceService.getService().getReferences(comment, PsiReferenceService.Hints.NO_HINTS).filterIsInstance<FileLocationPsiReference>()

    private fun singleReference(file: PsiFile): FileLocationPsiReference = ourReferences(file).single()

    private fun assertTarget(reference: FileLocationPsiReference, path: String, line: Int, column: Int?) {
        val target = reference.resolve() as FileLocationTarget
        assertEquals(myFixture.findFileInTempDir("src/Foo.php"), target.file)
        assertEquals(FileLocation(path, line, column), target.location)
        assertTrue(reference.isHighlightedWhenSoft)
    }
}
