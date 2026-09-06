package com.pronskiy.agenstorm.markdown

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormConfigurable
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import javax.swing.JComponent

/** Step F2.4: the Markdown group edits the checkbox and bullet options and every change reaches the feature through the settings topic. */
class MarkdownSettingsPanelTest : BasePlatformTestCase() {

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

    fun testOptionsShowTheDefaultsAndApplyWritesTheState() {
        assertTrue(named<JBCheckBox>("markdown.checkboxes").isSelected)
        assertTrue(named<JBCheckBox>("markdown.bullets").isSelected)
        assertFalse(configurable.isModified)

        named<JBCheckBox>("markdown.checkboxes").isSelected = false
        named<JBCheckBox>("markdown.bullets").isSelected = false
        assertTrue(configurable.isModified)
        configurable.apply()

        assertFalse(AgenstormSettings.getInstance().state.liveMarkupCheckboxes)
        assertFalse(AgenstormSettings.getInstance().state.liveMarkupBullets)
        assertFalse(configurable.isModified)
    }

    fun testRevealScopeSelectorOffersElementAndLine() {
        val combo = named<ComboBox<*>>("markdown.revealScope")
        assertEquals(listOf("element", "line"), (0 until combo.itemCount).map { (combo.getItemAt(it) as AgenstormConfigurable.RevealScopeOption).id })
        assertEquals("element", (combo.selectedItem as AgenstormConfigurable.RevealScopeOption).id)
        assertFalse(configurable.isModified)

        var fired = 0
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { fired++ })
        combo.selectedIndex = 1
        assertTrue(configurable.isModified)
        configurable.apply()
        assertEquals("line", AgenstormSettings.getInstance().state.liveMarkupRevealScope)
        assertEquals(1, fired)
    }

    fun testEveryChangedOptionFiresTheSettingsTopicOnce() {
        var fired = 0
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { fired++ })

        configurable.apply()
        assertEquals(0, fired)
        named<JBCheckBox>("markdown.bullets").isSelected = false
        configurable.apply()
        assertEquals(1, fired)
        named<JBCheckBox>("markdown.checkboxes").isSelected = false
        configurable.apply()
        assertEquals(2, fired)
    }

    private inline fun <reified T : JComponent> named(name: String): T =
        UIUtil.findComponentsOfType(panel, T::class.java).single { it.name == name }
}
