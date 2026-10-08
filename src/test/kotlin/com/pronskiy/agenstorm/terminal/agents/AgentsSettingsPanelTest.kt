package com.pronskiy.agenstorm.terminal.agents

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormConfigurable
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import javax.swing.JComponent

/** Step X1.1: the Agents group starts off with ten past sessions, and each change reaches the sidebar through the settings topic. */
class AgentsSettingsPanelTest : BasePlatformTestCase() {

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

    fun testOffWithTenPastSessionsByDefault() {
        val state = AgenstormSettings.State()
        assertFalse(state.agentSessionsEnabled)
        assertEquals(10, state.agentSessionsHistory)

        assertFalse(named<JBCheckBox>("agents.enabled").isSelected)
        assertEquals("10", named<JBTextField>("agents.history").text)
        assertFalse("the depth waits for the sidebar to be on", named<JBTextField>("agents.history").isEnabled)
        assertFalse(configurable.isModified)
    }

    fun testApplyWritesTheStateAndFiresTheTopicOncePerChange() {
        var fired = 0
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { fired++ })

        named<JBCheckBox>("agents.enabled").isSelected = true
        assertTrue(named<JBTextField>("agents.history").isEnabled)
        configurable.apply()
        assertTrue(AgenstormSettings.getInstance().state.agentSessionsEnabled)
        assertEquals(1, fired)

        named<JBTextField>("agents.history").text = "0"
        configurable.apply()
        assertEquals(0, AgenstormSettings.getInstance().state.agentSessionsHistory)
        assertEquals(2, fired)
        assertFalse(configurable.isModified)
    }

    private inline fun <reified T : JComponent> named(name: String): T =
        UIUtil.findComponentsOfType(panel, T::class.java).single { it.name == name }
}
