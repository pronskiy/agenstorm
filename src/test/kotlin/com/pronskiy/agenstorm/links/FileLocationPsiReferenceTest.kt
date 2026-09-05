package com.pronskiy.agenstorm.links

import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Step A2.2: the old-API soft reference used for comments and PHP strings. It is highlighted only when it
 * resolves, and its target navigates to the written line and column.
 */
class FileLocationPsiReferenceTest : BasePlatformTestCase() {

    private val text = "see src/Foo.php:3:5 and missing.php:1"

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("src/Foo.php", "<?php\n\n\$a = 1;\n    \$b = 2;\n")
    }

    fun testSoftReferenceWithRangeInsideTheHost() {
        val host = myFixture.configureByText("notes.txt", text)
        val reference = FileLocationPsiReference(host, FileLocationParser.parse(text).first())

        assertSame(host, reference.element)
        assertEquals(TextRange(4, 19), reference.rangeInElement)
        assertTrue(reference.isSoft)
        assertEmpty(reference.variants)
    }

    fun testResolvesToANavigatableTargetAndIsHighlighted() {
        val host = myFixture.configureByText("notes.txt", text)
        val reference = FileLocationPsiReference(host, FileLocationParser.parse(text).first())

        val target = reference.resolve() as FileLocationTarget
        assertEquals(myFixture.findFileInTempDir("src/Foo.php"), target.file)
        assertEquals(FileLocation("src/Foo.php", 3, 5), target.location)
        assertEquals("Foo.php:3", target.name)
        assertEquals(myFixture.findFileInTempDir("src/Foo.php"), target.containingFile!!.virtualFile)
        assertTrue(target.isValid)
        assertTrue(reference.isHighlightedWhenSoft)
        assertTrue(reference.isReferenceTo(target))
        assertEquals(target, FileLocationPsiReference(host, FileLocationParser.parse(text).first()).resolve())
    }

    fun testUnresolvedReferenceIsNeitherResolvedNorHighlighted() {
        val host = myFixture.configureByText("notes.txt", text)
        val reference = FileLocationPsiReference(host, FileLocationParser.parse(text)[1])

        assertNull(reference.resolve())
        assertFalse(reference.isHighlightedWhenSoft)
    }

    fun testTargetNavigatesToLineAndColumn() {
        val host = myFixture.configureByText("notes.txt", text)
        val target = FileLocationPsiReference(host, FileLocationParser.parse(text).first()).resolve() as FileLocationTarget
        assertTrue(target.canNavigate())

        target.navigate(true)

        val manager = FileEditorManager.getInstance(project)
        assertEquals(listOf(target.file), manager.selectedFiles.toList())
        assertEquals(LogicalPosition(2, 4), manager.selectedTextEditor!!.caretModel.logicalPosition)
    }
}
