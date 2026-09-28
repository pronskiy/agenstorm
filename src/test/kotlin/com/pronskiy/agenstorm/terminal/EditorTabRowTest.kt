package com.pronskiy.agenstorm.terminal

import com.intellij.icons.AllIcons
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.InplaceButton
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import java.awt.BorderLayout
import java.awt.Container
import javax.swing.JPanel

/**
 * Steps J1.8 and J1.9: how much of the editor tab row a maximized terminal covers, and which clicks are clicks on
 * a tab. Real `JBTabs`, laid out at a size of our choosing.
 */
class EditorTabRowTest : BasePlatformTestCase() {

    private lateinit var tabs: JBTabs
    private lateinit var first: TabInfo
    private lateinit var content: JPanel

    override fun setUp() {
        super.setUp()
        tabs = JBTabsFactory.createTabs(project, testRootDisposable)
        content = JPanel()
        first = tabs.addTab(TabInfo(content).setText("Foo.php"))
        tabs.addTab(TabInfo(JPanel()).setText("README.md"))
        tabs.select(first, false)
    }

    private fun layOut(height: Int) {
        tabs.component.setBounds(0, 0, 600, height)
        layOutTree(tabs.component)
    }

    private fun layOutTree(c: Container) {
        c.doLayout()
        c.components.filterIsInstance<Container>().forEach(::layOutTree)
    }

    fun testCoveredIsWhatFallsBelowTheVisibleBottom() {
        assertEquals(8, EditorTabRow.covered(headerBottom = 38, visibleBottom = 30))
        assertEquals(0, EditorTabRow.covered(headerBottom = 30, visibleBottom = 30))
        assertEquals(0, EditorTabRow.covered(headerBottom = 20, visibleBottom = 30))
    }

    fun testAWholeTabRowIsNotCovered() {
        layOut(400)
        assertEquals(0, EditorTabRow.coveredHeaderHeight(tabs))
    }

    fun testASqueezedTabRowReportsTheMissingPixels() {
        layOut(400)
        val header = tabs.getTabLabel(first)!!.height
        assertTrue("a tab row has a height, got $header", header > 10)

        layOut(10)

        val covered = EditorTabRow.coveredHeaderHeight(tabs)
        assertTrue("expected at least ${header - 10} px covered, got $covered", covered >= header - 10)
        // Given those pixels back, nothing is covered any more.
        layOut(10 + covered)
        assertEquals(0, EditorTabRow.coveredHeaderHeight(tabs))
    }

    fun testAClickOnATabLabelIsATabClick() {
        layOut(400)
        assertTrue(EditorTabRow.isTabLabelClick(tabs.getTabLabel(first)!!, tabs))
    }

    fun testAClickInTheEditorIsNot() {
        layOut(400)
        assertFalse(EditorTabRow.isTabLabelClick(content, tabs))
    }

    fun testAClickOnAButtonOnTheTabIsNot() {
        layOut(400)
        val label = tabs.getTabLabel(first) as Container
        val close = InplaceButton("Close", AllIcons.Actions.Close) {}
        label.add(close, BorderLayout.EAST)
        assertFalse(EditorTabRow.isTabLabelClick(close, tabs))
    }
}
