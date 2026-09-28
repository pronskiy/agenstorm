package com.pronskiy.agenstorm.statusbar

import junit.framework.TestCase
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar

/**
 * Epic S: the status text is found by its class name under the status bar and hidden, and nothing beside it is.
 * The platform's `StatusPanel` is package-private, so a class of our own stands in for it by name.
 */
class StatusTextHiderTest : TestCase() {

    private class StandIn : JPanel()

    private val standInName = StandIn::class.java.name

    private lateinit var bar: JPanel
    private lateinit var text: StandIn
    private lateinit var progress: JProgressBar

    override fun setUp() {
        super.setUp()
        text = StandIn()
        progress = JProgressBar()
        val infoAndProgress = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply { add(text, BorderLayout.CENTER) }, BorderLayout.CENTER)
            add(progress, BorderLayout.EAST)
        }
        bar = JPanel(BorderLayout()).apply {
            add(JLabel("main"), BorderLayout.WEST)
            add(infoAndProgress, BorderLayout.CENTER)
        }
    }

    fun testFindsTheTextWhereverItIsNested() {
        assertEquals(listOf(text), StatusTextHider.findByClassName(bar, standInName))
    }

    fun testFindsNothingInABarWithoutIt() {
        assertTrue(StatusTextHider.findByClassName(JPanel().apply { add(JLabel()) }, standInName).isEmpty())
    }

    fun testHidesOnlyTheText() {
        assertTrue(setShown(show = false))

        assertFalse(text.isVisible)
        assertTrue(progress.isVisible)
    }

    fun testShowsItAgain() {
        setShown(show = false)
        setShown(show = true)

        assertTrue(text.isVisible)
    }

    fun testABarLaidOutDifferentlyIsLeftAlone() {
        val other = JPanel().apply { add(JLabel("text")) }

        assertFalse(StatusTextHider.setShown(other, show = false, className = standInName))
        assertTrue(other.components.all { it.isVisible })
    }

    /** The platform's class name is spelled out in one place; this keeps the spelling honest. */
    fun testThePlatformClassNameIsTheStatusPanel() {
        assertEquals("com.intellij.openapi.wm.impl.status.StatusPanel", StatusTextHider.STATUS_PANEL_CLASS)
        assertNotNull(Class.forName(StatusTextHider.STATUS_PANEL_CLASS, false, javaClass.classLoader))
    }

    private fun setShown(show: Boolean): Boolean = StatusTextHider.setShown(bar, show, standInName)
}
