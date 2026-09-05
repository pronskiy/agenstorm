package com.pronskiy.agenstorm.links.markdown

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.links.FileLocation
import com.pronskiy.agenstorm.links.FileLocationSymbol
import com.pronskiy.agenstorm.links.FileLocationSymbolReference
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownLinkDestination
import org.intellij.plugins.markdown.lang.references.paths.MarkdownUnresolvedFileReferenceInspection

/**
 * Step A1.4: the provider is wired to `MarkdownLinkDestination` through `agenstorm-markdown.xml`, HyperlinkAnnotator
 * draws the link, and the Markdown "unresolved file" inspection stays quiet on resolvable locations.
 */
class MarkdownLocationReferenceProviderTest : BasePlatformTestCase() {

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

    fun testLinkDestinationWithLocationGetsAResolvedReference() {
        val destination = destination("[x](src/Foo.php:3:5)")

        val reference = references(destination).single()
        assertEquals(destination.textRange.startOffset, reference.absoluteRange.startOffset)
        assertEquals("src/Foo.php:3:5", reference.absoluteRange.substring(destination.containingFile.text))

        val symbol = reference.resolveReference().single() as FileLocationSymbol
        assertEquals(myFixture.findFileInTempDir("src/Foo.php"), symbol.file)
        assertEquals(FileLocation("src/Foo.php", 3, 5), symbol.location)
    }

    fun testPlainPathsAndAnchorsAreLeftToTheMarkdownPlugin() {
        assertEmpty(references(destination("[x](src/Foo.php)")))
        assertEmpty(references(destination("[x](#heading)")))
        assertEmpty(references(destination("[x](https://example.com/a/b.php:42)")))
    }

    fun testUnresolvedLocationGetsNoReference() {
        assertEmpty(references(destination("[x](missing.php:1)")))
    }

    fun testLocationLinkIsHighlightedAsAReferenceByHyperlinkAnnotator() {
        val destination = destination("[x](src/Foo.php:3:5)")

        val infos = myFixture.doHighlighting()
        val linkInfo = infos.filter { it.startOffset == destination.textRange.startOffset && it.endOffset == destination.textRange.endOffset }
            .singleOrNull { it.forcedTextAttributesKey == DefaultLanguageHighlighterColors.HIGHLIGHTED_REFERENCE }
        assertNotNull("no HIGHLIGHTED_REFERENCE over the destination; got ${infos.map { "${it.severity} [${it.startOffset},${it.endOffset}] ${it.forcedTextAttributesKey?.externalName}" }}", linkInfo)
        assertEquals(HighlightSeverity.INFORMATION, linkInfo!!.severity)
    }

    fun testUnresolvedFileInspectionIsSuppressedOnlyForResolvableLocations() {
        myFixture.enableInspections(MarkdownUnresolvedFileReferenceInspection::class.java)

        destination("[x](src/Foo.php:3:5)")
        assertEmpty(myFixture.doHighlighting().filter { it.severity == HighlightSeverity.WARNING })

        destination("[x](missing.php:3)")
        val warning = myFixture.doHighlighting().single { it.severity == HighlightSeverity.WARNING }
        assertEquals("Cannot resolve file 'missing.php:3'", warning.description)
    }

    fun testFeatureToggleOffDisablesTheProvider() {
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State(linksEnabled = false))
        assertEmpty(references(destination("[x](src/Foo.php:3:5)")))
    }

    private fun destination(markdown: String): MarkdownLinkDestination {
        val file = myFixture.configureByText("notes.md", markdown)
        return PsiTreeUtil.findChildrenOfType(file, MarkdownLinkDestination::class.java).single()
    }

    private fun references(destination: MarkdownLinkDestination): Collection<FileLocationSymbolReference> =
        PsiSymbolReferenceService.getService().getReferences(destination, FileLocationSymbolReference::class.java)
}
