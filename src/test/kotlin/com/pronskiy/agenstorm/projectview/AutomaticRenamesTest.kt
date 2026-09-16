package com.pronskiy.agenstorm.projectview

import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.refactoring.rename.naming.NameSuggester
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Decision 62: the inline rename answers the automatic-renaming dialog the way its OK button would, without
 * showing it — every suggestion when the renamer is selected by default, none when it is not.
 */
class AutomaticRenamesTest : BasePlatformTestCase() {

    /** Suggests the given name for each element; an element mapped to null gets no suggestion. */
    private class FakeRenamer(private val selected: Boolean, private val suggestions: Map<PsiNamedElement, String?>) : AutomaticRenamer() {
        init {
            myElements.addAll(suggestions.keys)
            suggestAllNames("old", "new")
        }
        override fun suggestNameForElement(element: PsiNamedElement, suggester: NameSuggester, newClassName: String, oldClassName: String): String =
            suggestions[element] ?: element.name!!
        override fun isSelectedByDefault(): Boolean = selected
        override fun getDialogTitle(): String = "t"
        override fun getDialogDescription(): String = "d"
        override fun entityName(): String = "e"
    }

    private fun element(name: String) = myFixture.addFileToProject("root/$name", "")

    fun testASelectedByDefaultRenamerAppliesEverySuggestion() {
        val a = element("A.php")
        val b = element("B.php")
        val renamer = FakeRenamer(selected = true, mapOf(a to "A2.php", b to "B2.php"))

        AutomaticRenames.acceptAsOk(renamer)

        assertEquals(mapOf(a to "A2.php", b to "B2.php"), renamer.renames.filterValues { it != null })
    }

    fun testARenamerNotSelectedByDefaultAppliesNothing() {
        val a = element("A.php")
        val renamer = FakeRenamer(selected = false, mapOf(a to "A2.php"))

        AutomaticRenames.acceptAsOk(renamer)

        assertTrue(renamer.renames.filterValues { it != null }.isEmpty())
    }

    fun testASuggestionWithNoNameIsLeftAlone() {
        val a = element("A.php")
        val b = element("B.php")
        val renamer = FakeRenamer(selected = true, mapOf(a to "A2.php", b to null))

        AutomaticRenames.acceptAsOk(renamer)

        assertEquals("A2.php", renamer.renames[a])
        assertNull(renamer.renames[b])
    }
}
