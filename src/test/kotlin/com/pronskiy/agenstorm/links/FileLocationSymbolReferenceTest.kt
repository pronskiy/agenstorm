package com.pronskiy.agenstorm.links

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Step A1.3: the Symbol-API reference resolves to a [FileLocationSymbol] whose navigation target points at
 * the written line and column; unresolvable locations produce no reference at all.
 */
class FileLocationSymbolReferenceTest : BasePlatformTestCase() {

    private val text = "see src/Foo.php:3:5 and missing.php:1"

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("src/Foo.php", "<?php\n\n\$a = 1;\n    \$b = 2;\n")
    }

    fun testReferenceExposesHostAndRange() {
        val host = myFixture.configureByText("notes.txt", text)
        val match = FileLocationParser.parse(text).first()

        val reference = FileLocationSymbolReference.create(host, match)!!

        assertSame(host, reference.element)
        assertEquals(TextRange(4, 19), reference.rangeInElement)
        assertEquals("src/Foo.php:3:5", reference.absoluteRange.substring(text))
    }

    fun testResolvesToASymbolForTheWrittenLocation() {
        val host = myFixture.configureByText("notes.txt", text)
        val reference = FileLocationSymbolReference.create(host, FileLocationParser.parse(text).first())!!

        val symbol = reference.resolveReference().single() as FileLocationSymbol
        assertEquals(myFixture.findFileInTempDir("src/Foo.php"), symbol.file)
        assertEquals(FileLocation("src/Foo.php", 3, 5), symbol.location)
        assertTrue(reference.resolvesTo(symbol))
        assertEquals(symbol, symbol.createPointer().dereference())
        assertEquals(symbol, FileLocationSymbol(symbol.file, symbol.location))
    }

    fun testNavigationTargetPointsAtLineAndColumn() {
        val host = myFixture.configureByText("notes.txt", text)
        val reference = FileLocationSymbolReference.create(host, FileLocationParser.parse(text).first())!!
        val symbol = reference.resolveReference().single() as FileLocationSymbol

        val target = symbol.getNavigationTargets(project).single() as FileLocationNavigationTarget
        assertEquals(symbol.file, target.file)
        // "<?php\n" "\n" "$a = 1;\n": line 3 starts at offset 7, column 5 is offset 11.
        assertEquals(11, target.offset)
        // navigationRequest() is a background-thread API: the platform calls it in a non-blocking read action.
        val request = ApplicationManager.getApplication().executeOnPooledThread<NavigationRequest?> {
            ReadAction.compute<NavigationRequest?, Throwable> { target.navigationRequest() }
        }.get()
        assertNotNull(request)
        assertEquals("Foo.php:3", target.computePresentation().presentableText)
        assertEquals(target, target.createPointer().dereference())
    }

    fun testUnresolvableLocationYieldsNoReference() {
        val host = myFixture.configureByText("notes.txt", text)
        val missing = FileLocationParser.parse(text)[1]
        assertEquals(FileLocation("missing.php", 1, null), missing.location)

        assertNull(FileLocationSymbolReference.create(host, missing))
    }

    fun testSymbolWithDeletedFileHasNoTargets() {
        val host = myFixture.configureByText("notes.txt", text)
        val reference = FileLocationSymbolReference.create(host, FileLocationParser.parse(text).first())!!
        val symbol = reference.resolveReference().single() as FileLocationSymbol

        WriteAction.run<Throwable> { symbol.file.delete(this) }

        assertEmpty(reference.resolveReference())
        assertEmpty(symbol.getNavigationTargets(project))
        assertNull(symbol.createPointer().dereference())
    }
}
