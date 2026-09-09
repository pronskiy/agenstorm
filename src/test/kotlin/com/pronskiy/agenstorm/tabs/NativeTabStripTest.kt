package com.pronskiy.agenstorm.tabs

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JPanel
import javax.swing.JRootPane

/**
 * Step E1.6: the platform's macOS tab row is hidden while the toolbar strip is on, and only a row Agenstorm hid
 * is ever given back — to the visibility the platform itself would have chosen.
 */
class NativeTabStripTest : BasePlatformTestCase() {

    private fun row(visible: Boolean = true) = JPanel().apply { isVisible = visible }

    fun testFeatureOnHidesAVisibleRow() {
        val container = row()

        assertTrue(NativeTabStrip.apply(container, featureOn = true, tabCount = 2))
        assertFalse(container.isVisible)

        assertFalse("idempotent", NativeTabStrip.apply(container, featureOn = true, tabCount = 2))
        assertFalse(container.isVisible)
    }

    fun testFeatureOffGivesBackOnlyWhatWeHid() {
        val container = row()
        NativeTabStrip.apply(container, featureOn = true, tabCount = 2)

        assertTrue(NativeTabStrip.apply(container, featureOn = false, tabCount = 2))
        assertTrue(container.isVisible)
    }

    fun testFeatureOffRestoresThePlatformsOwnRuleOfMoreThanOneTab() {
        val container = row()
        NativeTabStrip.apply(container, featureOn = true, tabCount = 2)

        assertFalse("one project means the platform would hide it too", NativeTabStrip.apply(container, featureOn = false, tabCount = 1))
        assertFalse(container.isVisible)
    }

    fun testARowWeNeverHidIsLeftAlone() {
        val hiddenByThePlatform = row(visible = false)
        assertFalse(NativeTabStrip.apply(hiddenByThePlatform, featureOn = false, tabCount = 2))
        assertFalse(hiddenByThePlatform.isVisible)

        val shownByThePlatform = row()
        assertFalse(NativeTabStrip.apply(shownByThePlatform, featureOn = false, tabCount = 2))
        assertTrue(shownByThePlatform.isVisible)
    }

    fun testARowTheFeatureFoundAlreadyHiddenIsNotClaimed() {
        val container = row(visible = false)

        assertFalse(NativeTabStrip.apply(container, featureOn = true, tabCount = 1))
        assertFalse(NativeTabStrip.apply(container, featureOn = false, tabCount = 2))
        assertFalse("we did not hide it, so we do not show it", container.isVisible)
    }

    fun testTheRowIsFoundThroughTheRootPaneClientProperty() {
        val rootPane = JRootPane()
        assertNull(NativeTabStrip.containerOf(rootPane))
        assertNull(NativeTabStrip.containerOf(null))

        val container = row()
        rootPane.putClientProperty(NativeTabStrip.CONTAINER_KEY, container)
        assertSame(container, NativeTabStrip.containerOf(rootPane))
    }
}
