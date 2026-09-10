package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormConfigurable
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import javax.swing.JComponent

/**
 * Step G2.3: the Terminal group shows the three options, Apply writes them and fires the settings topic,
 * and turning the feature off unbinds the endpoints that are listening.
 *
 * Step K1.5 adds the Terminal editor group beside it, and with it the rule the endpoint's lifetime now
 * follows: one endpoint serves both features, so it stays bound while either of them is on.
 */
class TerminalSettingsPanelTest : BasePlatformTestCase() {

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
            project.service<OpenRequestServer>().stop()
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testTheGroupShowsTheDefaults() {
        assertTrue(featureToggle().isSelected)
        assertEquals("open", named<JBTextField>("terminal.commandNames").text)
        assertFalse(named<JBCheckBox>("terminal.unknownFileTypes").isSelected)
        assertFalse(configurable.isModified)
    }

    fun testApplyWritesEveryOptionAndFiresTheSettingsTopic() {
        var fired = 0
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { fired++ })

        named<JBTextField>("terminal.commandNames").text = "open, e"
        named<JBCheckBox>("terminal.unknownFileTypes").isSelected = true
        assertTrue(configurable.isModified)
        configurable.apply()

        val state = AgenstormSettings.getInstance().state
        assertEquals("open, e", state.terminalOpenCommandNames)
        assertTrue(state.terminalOpenUnknownFileTypes)
        assertEquals(2, fired)
        assertFalse(configurable.isModified)
    }

    fun testTurningBothFeaturesOffUnbindsAListeningEndpoint() {
        val server = project.service<OpenRequestServer>()
        assertTrue(server.start() > 0)

        featureToggle("settings.terminal.open.enabled").isSelected = false
        featureToggle("settings.terminal.editor.enabled").isSelected = false
        configurable.apply()

        assertFalse(AgenstormSettings.getInstance().state.terminalOpenEnabled)
        assertFalse(AgenstormSettings.getInstance().state.terminalEditorEnabled)
        assertEquals("nothing may be listening while both features are off", -1, server.port)
    }

    fun testTheEndpointSurvivesTheOtherFeatureBeingSwitchedOff() {
        val server = project.service<OpenRequestServer>()
        assertTrue(server.start() > 0)

        featureToggle("settings.terminal.open.enabled").isSelected = false
        configurable.apply()

        assertTrue("the \$EDITOR bridge posts to this very endpoint", server.port > 0)
    }

    fun testTheEditorGroupIsThereAndOnByDefault() {
        assertTrue(featureToggle("settings.terminal.editor.enabled").isSelected)

        featureToggle("settings.terminal.editor.enabled").isSelected = false
        assertTrue(configurable.isModified)
        configurable.apply()

        assertFalse(AgenstormSettings.getInstance().state.terminalEditorEnabled)
    }

    /** The group's own switch has no component name — [AgenstormConfigurable.featureGroup] labels it instead. */
    private fun featureToggle(key: String = "settings.terminal.open.enabled"): JBCheckBox =
        UIUtil.findComponentsOfType(panel, JBCheckBox::class.java)
            .single { it.text == AgenstormBundle.message(key) }

    private inline fun <reified T : JComponent> named(name: String): T =
        UIUtil.findComponentsOfType(panel, T::class.java).single { it.name == name }
}
