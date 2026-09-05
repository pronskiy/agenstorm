package com.pronskiy.agenstorm.links.markdown

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.links.FileLocation
import com.pronskiy.agenstorm.links.FileLocationNavigationTarget
import com.pronskiy.agenstorm.links.FileLocationSymbol
import com.pronskiy.agenstorm.links.FileLocationSymbolReference
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination
import org.intellij.plugins.markdown.lang.references.paths.MarkdownUnresolvedFileReferenceInspection

/**
 * Step A1.5: end-to-end over the `testData/links/md/` fixture project. `docs/plan.md` links to a file
 * relative to its own directory, to a file at the root, to a missing file, to a root-relative path and
 * to a heading anchor that the Markdown plugin resolves on its own.
 */
class MarkdownLocationReferenceTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData/links/md"

    override fun setUp() {
        super.setUp()
        myFixture.copyDirectoryToProject("", "")
        myFixture.configureFromTempProjectFile("docs/plan.md")
    }

    fun testEveryLinkResolvesAsWritten() {
        val byText = destinations()
        assertEquals(setOf("../src/Foo.php:3:5", "../README.md:1", "missing.php:1", "src/Foo.php:2", "../README.md#notes"), byText.keys)

        val foo = myFixture.findFileInTempDir("src/Foo.php")
        val readme = myFixture.findFileInTempDir("README.md")

        assertEquals(FileLocationSymbol(foo, FileLocation("../src/Foo.php", 3, 5)), symbol(byText.getValue("../src/Foo.php:3:5")))
        assertEquals(FileLocationSymbol(readme, FileLocation("../README.md", 1, null)), symbol(byText.getValue("../README.md:1")))
        assertEquals(FileLocationSymbol(foo, FileLocation("src/Foo.php", 2, null)), symbol(byText.getValue("src/Foo.php:2")))
        assertEmpty(ourReferences(byText.getValue("missing.php:1")))
        assertEmpty(ourReferences(byText.getValue("../README.md#notes")))
    }

    fun testNavigationOffsetMatchesLineAndColumnOfTheTargetDocument() {
        val symbol = symbol(destinations().getValue("../src/Foo.php:3:5"))
        val document = FileDocumentManager.getInstance().getDocument(symbol.file)!!
        val expected = document.getLineStartOffset(2) + 4

        val target = symbol.getNavigationTargets(project).single() as FileLocationNavigationTarget
        assertEquals(expected, target.offset)
        assertEquals("\$a = 1;", document.text.lines()[2])
        assertEquals(4, target.offset - document.getLineStartOffset(2))
    }

    fun testLineOnlyLocationNavigatesToTheLineStart() {
        val symbol = symbol(destinations().getValue("../README.md:1"))
        val target = symbol.getNavigationTargets(project).single() as FileLocationNavigationTarget
        assertEquals(0, target.offset)
    }

    fun testOnlyResolvableLocationsAreHighlightedAndEachExactlyOnce() {
        myFixture.enableInspections(MarkdownUnresolvedFileReferenceInspection::class.java)
        val infos = myFixture.doHighlighting()
        val byText = destinations()

        val linkHighlights = infos.filter { it.forcedTextAttributesKey == DefaultLanguageHighlighterColors.HIGHLIGHTED_REFERENCE }
        val highlighted = linkHighlights.map { info ->
            byText.values.single { it.textRange.startOffset == info.startOffset && it.textRange.endOffset == info.endOffset }.text
        }
        assertEquals(listOf("../src/Foo.php:3:5", "../README.md:1", "src/Foo.php:2"), highlighted)

        val warnings = infos.filter { it.severity == HighlightSeverity.WARNING }.map { it.description }
        assertEquals(listOf("Cannot resolve file 'missing.php:1'"), warnings)
    }

    fun testHeadingAnchorsStillResolveThroughTheMarkdownPlugin() {
        val anchor = destinations().getValue("../README.md#notes")
        val references = PsiSymbolReferenceService.getService().getReferences(anchor)
        assertTrue("expected the Markdown plugin's own anchor reference, got $references", references.isNotEmpty())
        assertTrue(references.none { it is FileLocationSymbolReference })
        assertTrue(references.all { it.resolveReference().isNotEmpty() })
    }

    private fun destinations(): Map<String, MarkdownLinkDestination> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, MarkdownLinkDestination::class.java).associateBy { it.text }

    private fun ourReferences(destination: MarkdownLinkDestination): Collection<FileLocationSymbolReference> =
        PsiSymbolReferenceService.getService().getReferences(destination, FileLocationSymbolReference::class.java)

    private fun symbol(destination: MarkdownLinkDestination): FileLocationSymbol =
        ourReferences(destination).single().resolveReference().single() as FileLocationSymbol
}
