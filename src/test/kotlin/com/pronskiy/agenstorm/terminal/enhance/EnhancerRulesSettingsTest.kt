package com.pronskiy.agenstorm.terminal.enhance

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.TableView
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormConfigurable
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import javax.swing.JComponent

/** Step I3.1: the Terminal output group lists every rule with a switch, and Apply writes the switched-off ids and fires the settings topic. */
class EnhancerRulesSettingsTest : BasePlatformTestCase() {

    private lateinit var configurable: AgenstormConfigurable
    private lateinit var panel: JComponent

    override fun setUp() {
        super.setUp()
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        RuleRepository.getInstance().forgetForTest()
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

    @Suppress("UNCHECKED_CAST")
    private fun table(): TableView<EnhancerRulesTable.Row> =
        UIUtil.findComponentsOfType(panel, TableView::class.java).single { it.name == "terminal.enhancer.rules" } as TableView<EnhancerRulesTable.Row>

    fun testTheTableListsTheBuiltInsSwitchedOn() {
        val rows = table().listTableModel.items

        assertEquals(listOf("php-var-dump", "php-print-r", "php-var-export", "php-stack-trace", "json-line"), rows.map { it.id })
        assertTrue(rows.all { it.enabled && it.source == "built-in" })
        assertFalse(configurable.isModified)
    }

    fun testSwitchingARuleOffIsAppliedAndFiresTheTopic() {
        var fired = 0
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { fired++ })
        val model = table().listTableModel
        model.setValueAt(false, 4, 2)

        assertTrue(configurable.isModified)
        configurable.apply()

        assertEquals(listOf("json-line"), AgenstormSettings.getInstance().state.terminalEnhancerDisabledRules)
        assertFalse(RuleRepository.getInstance().activeRules().any { it.id == "json-line" })
        assertTrue(fired >= 1)
        assertFalse(configurable.isModified)
    }
}
