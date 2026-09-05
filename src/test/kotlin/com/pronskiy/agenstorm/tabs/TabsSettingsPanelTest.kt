package com.pronskiy.agenstorm.tabs

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormConfigurable
import com.pronskiy.agenstorm.core.AgenstormSettings
import java.awt.Component
import javax.swing.JComponent

/** Step E2.4: the Project tabs group edits mirror bounds, icons and max width. */
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
        assertTrue(named<JBCheckBox>("tabs.mirrorBounds").isSelected)
        assertTrue(named<JBCheckBox>("tabs.showIcons").isSelected)
        assertEquals("220", named<JBTextField>("tabs.maxWidth").text)
        assertFalse(configurable.isModified)

        named<JBCheckBox>("tabs.mirrorBounds").isSelected = false
        named<JBCheckBox>("tabs.showIcons").isSelected = false
        named<JBTextField>("tabs.maxWidth").text = "160"
        assertTrue(configurable.isModified)

        configurable.apply()

        val state = AgenstormSettings.getInstance().state
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

    private inline fun <reified T : Component> named(name: String): T {
        val component = UIUtil.uiTraverser(panel).filter { it.name == name }.first()
        assertNotNull("no component named $name", component)
        return component as T
    }
}
