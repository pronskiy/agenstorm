package com.pronskiy.agenstorm.tabs

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormConfigurable
import com.pronskiy.agenstorm.core.AgenstormSettings
import java.awt.Component
import javax.swing.JComponent

/** Steps E2.4 / P2.8: the Project tabs group edits mirror bounds, icons, max width and the offload rows. */
class TabsSettingsPanelTest : BasePlatformTestCase() {

    private lateinit var configurable: AgenstormConfigurable
    private lateinit var panel: JComponent

    override fun setUp() {
        super.setUp()
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        configurable = AgenstormConfigurable()
        panel = configurable.createComponent()!!
    }

    override fun tearDown() {
        try {
            configurable.disposeUIResources()
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testFieldsShowTheDefaultsAndApplyWritesTheState() {
        assertTrue(named<JBCheckBox>("tabs.branchInStatusBar").isSelected)
        assertTrue(named<JBCheckBox>("tabs.mirrorBounds").isSelected)
        assertTrue(named<JBCheckBox>("tabs.showIcons").isSelected)
        assertEquals("220", named<JBTextField>("tabs.maxWidth").text)
        assertFalse(configurable.isModified)

        named<JBCheckBox>("tabs.branchInStatusBar").isSelected = false
        named<JBCheckBox>("tabs.mirrorBounds").isSelected = false
        named<JBCheckBox>("tabs.showIcons").isSelected = false
        named<JBTextField>("tabs.maxWidth").text = "160"
        assertTrue(configurable.isModified)

        configurable.apply()

        val state = AgenstormSettings.getInstance().state
        assertFalse(state.branchInStatusBar)
        assertFalse(state.tabsMirrorWindowBounds)
        assertFalse(state.tabsShowIcons)
        assertEquals(160, state.tabsMaxWidth)
        assertFalse(configurable.isModified)
    }

    fun testChangingTheLookRefreshesEveryStripButAnUnchangedPageDoesNot() {
        val model = ProjectTabsModel.getInstance()
        var refreshed = 0
        model.addListener({ refreshed++ }, testRootDisposable)

        configurable.apply()
        assertEquals("nothing changed, nothing to redraw", 0, refreshed)

        named<JBCheckBox>("tabs.showIcons").isSelected = false
        configurable.apply()
        assertEquals(1, refreshed)

        named<JBTextField>("tabs.maxWidth").text = "150"
        configurable.apply()
        assertEquals(2, refreshed)
    }

    fun testTogglingTheBranchOptionNotifiesSettingsListeners() {
        var fired = 0
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(com.pronskiy.agenstorm.core.AgenstormSettingsListener.TOPIC, com.pronskiy.agenstorm.core.AgenstormSettingsListener { fired++ })

        configurable.apply()
        assertEquals(0, fired)

        named<JBCheckBox>("tabs.branchInStatusBar").isSelected = false
        configurable.apply()
        assertEquals(1, fired)
    }

    /** Step P2.8: the offload rows — switch, idle minutes, cap — show their defaults and are written on apply. */
    fun testOffloadRowsShowTheDefaultsAndApplyWritesThem() {
        assertTrue(named<JBCheckBox>("tabs.offload.enabled").isSelected)
        assertEquals("120", named<JBTextField>("tabs.offload.minutes").text)
        assertEquals("8", named<JBTextField>("tabs.offload.maxLoaded").text)
        assertFalse(configurable.isModified)

        named<JBCheckBox>("tabs.offload.enabled").isSelected = false
        named<JBTextField>("tabs.offload.minutes").text = "45"
        named<JBTextField>("tabs.offload.maxLoaded").text = "3"
        assertTrue(configurable.isModified)
        configurable.apply()

        val state = AgenstormSettings.getInstance().state
        assertFalse(state.projectsOffloadEnabled)
        assertEquals(45, state.projectsOffloadAfterMinutes)
        assertEquals(3, state.projectsMaxLoaded)
        assertFalse(configurable.isModified)
    }

    private inline fun <reified T : Component> named(name: String): T {
        val component = UIUtil.uiTraverser(panel).filter { it.name == name }.first()
        assertNotNull("no component named $name", component)
        return component as T
    }
}
