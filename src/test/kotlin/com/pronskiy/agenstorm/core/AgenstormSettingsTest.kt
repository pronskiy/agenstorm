package com.pronskiy.agenstorm.core

import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer

/**
 * Settings logic for step 01.3: one Boolean toggle per feature, persisted to `agenstorm.xml`,
 * edited through [AgenstormConfigurable].
 */
class AgenstormSettingsTest : BasePlatformTestCase() {

    private lateinit var settings: AgenstormSettings

    override fun setUp() {
        super.setUp()
        settings = AgenstormSettings.getInstance()
        settings.loadState(AgenstormSettings.State())
    }

    override fun tearDown() {
        try {
            // The application-level service outlives this test; never leak a modified state into the next one.
            settings.loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testEveryFeatureIsEnabledByDefault() {
        val state = AgenstormSettings.State()
        assertTrue(state.linksEnabled)
        assertTrue(state.scratchFilterEnabled)
        assertTrue(state.hideFileNameInTitle)
        assertTrue(state.commitEnabled)
        assertTrue(state.projectTabsEnabled)
        assertTrue(state.liveMarkupEnabled)
    }

    fun testStateIsStoredInAgenstormXml() {
        val annotation = AgenstormSettings::class.java.getAnnotation(State::class.java)
        assertNotNull("AgenstormSettings must be annotated with @State", annotation)
        assertEquals("Agenstorm", annotation!!.name)
        assertEquals(listOf("agenstorm.xml"), annotation.storages.map(Storage::value))
    }

    fun testLoadStateReplacesTheCurrentState() {
        settings.loadState(AgenstormSettings.State(linksEnabled = false, liveMarkupEnabled = false))

        assertFalse(settings.state.linksEnabled)
        assertFalse(settings.state.liveMarkupEnabled)
        assertTrue(settings.state.commitEnabled)
    }

    fun testStateSerializesOnlyNonDefaultTogglesUnderStableNames() {
        val state = AgenstormSettings.State(hideFileNameInTitle = false, projectTabsEnabled = false)

        // The component store saves PersistentStateComponent state with the skip-defaults filter.
        val element = XmlSerializer.serialize(state, SkipDefaultsSerializationFilter())
        val options = element.getChildren("option").associate { it.getAttributeValue("name") to it.getAttributeValue("value") }

        assertEquals(mapOf("hideFileNameInTitle" to "false", "projectTabsEnabled" to "false"), options)

        val restored = XmlSerializer.deserialize(element, AgenstormSettings.State::class.java)
        assertEquals(state, restored)
    }

    fun testScratchAllowListRoundTripsThroughXml() {
        val state = AgenstormSettings.State(scratchAllowedFileTypes = mutableListOf("JSON", "PHP"))

        val element = XmlSerializer.serialize(state, SkipDefaultsSerializationFilter())
        val option = element.getChildren("option").single()
        assertEquals("scratchAllowedFileTypes", option.getAttributeValue("name"))
        assertEquals(listOf("JSON", "PHP"), option.getChild("list").getChildren("option").map { it.getAttributeValue("value") })

        assertEquals(state, XmlSerializer.deserialize(element, AgenstormSettings.State::class.java))
    }

    fun testScratchAllowListIsEditedAsOneNamePerLine() {
        val configurable = AgenstormConfigurable()
        try {
            val panel = configurable.createComponent()!!
            val area = UIUtil.findComponentsOfType(panel, JBTextArea::class.java).single { it.name == "scratch.allowList" }
            assertEquals("PLAIN_TEXT\nMarkdown\nPHP\nJavaScript", area.text)
            assertFalse(configurable.isModified)

            area.text = "JSON\n\n  PHP \nJSON\n"
            assertTrue(configurable.isModified)
            configurable.apply()
            assertEquals(listOf("JSON", "PHP"), settings.state.scratchAllowedFileTypes)
            assertFalse(configurable.isModified)

            settings.loadState(AgenstormSettings.State())
            configurable.reset()
            assertEquals("PLAIN_TEXT\nMarkdown\nPHP\nJavaScript", area.text)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testConfigurableShowsOneTogglePerFeatureAndAppliesChanges() {
        val configurable = AgenstormConfigurable()
        try {
            // createComponent() is what the Settings dialog calls; it keeps the panel that isModified/apply/reset operate on.
            val panel = configurable.createComponent()!!
            val featureTexts = listOf(
                "settings.links.enabled", "settings.scratch.enabled", "settings.frame.hideFileName",
                "settings.commit.enabled", "settings.tabs.enabled", "settings.markdown.liveMarkup.enabled",
            ).map(AgenstormBundle::message)
            val checkBoxes = UIUtil.findComponentsOfType(panel, JBCheckBox::class.java).filter { it.text in featureTexts }

            assertEquals(6, checkBoxes.size)
            assertTrue(checkBoxes.all { it.isSelected })
            assertFalse(configurable.isModified)

            val linksToggle = checkBoxes.single { it.text == AgenstormBundle.message("settings.links.enabled") }
            linksToggle.isSelected = false
            assertTrue(configurable.isModified)

            configurable.apply()
            assertFalse(settings.state.linksEnabled)
            assertTrue(settings.state.scratchFilterEnabled)
            assertFalse(configurable.isModified)

            settings.loadState(AgenstormSettings.State())
            configurable.reset()
            assertTrue(linksToggle.isSelected)
        } finally {
            configurable.disposeUIResources()
        }
    }
}
