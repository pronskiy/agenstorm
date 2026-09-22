package com.pronskiy.agenstorm.terminal.enhance

import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormSettings
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/**
 * Step I3.1. The rules table on the settings page: id, where the rule comes from, and whether it is on. The
 * "on" column edits a copy; [apply] writes the ids that are off to the settings, [reset] reads them back, and
 * [isModified] is what the page asks before enabling Apply — the same three the Kotlin UI DSL wants of a cell.
 */
class EnhancerRulesTable(private val repository: RuleRepository = RuleRepository.getInstance()) {

    class Row(val id: String, val source: String, var enabled: Boolean)

    private val model = ListTableModel<Row>(IdColumn(), SourceColumn(), EnabledColumn())
    val table: TableView<Row> = TableView(model).apply {
        name = "terminal.enhancer.rules"
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
        visibleRowCount = 6
    }
    val component: JComponent get() = table

    init {
        reset()
    }

    fun rows(): List<Row> = model.items

    /** The table again from the repository and the settings. */
    fun reset() {
        val disabled = AgenstormSettings.getInstance().state.terminalEnhancerDisabledRules
        model.items = repository.rules.map { Row(it.id, sourceLabel(it), it.id !in disabled) }
    }

    fun isModified(): Boolean = disabledIds() != AgenstormSettings.getInstance().state.terminalEnhancerDisabledRules.toList()

    fun apply() {
        AgenstormSettings.getInstance().state.terminalEnhancerDisabledRules = disabledIds().toMutableList()
    }

    private fun disabledIds(): List<String> = model.items.filter { !it.enabled }.map { it.id }

    private fun sourceLabel(rule: EnhancerRule): String =
        if (rule.source == EnhancerRule.BUILT_IN_SOURCE) AgenstormBundle.message("terminal.enhancer.rules.builtIn") else rule.source

    private class IdColumn : ColumnInfo<Row, String>(AgenstormBundle.message("settings.terminal.enhancer.rules.column.id")) {
        override fun valueOf(item: Row): String = item.id
    }

    private class SourceColumn : ColumnInfo<Row, String>(AgenstormBundle.message("settings.terminal.enhancer.rules.column.source")) {
        override fun valueOf(item: Row): String = item.source
    }

    private class EnabledColumn : ColumnInfo<Row, Boolean>(AgenstormBundle.message("settings.terminal.enhancer.rules.column.enabled")) {
        override fun valueOf(item: Row): Boolean = item.enabled
        override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java
        override fun isCellEditable(item: Row): Boolean = true
        override fun setValue(item: Row, value: Boolean) {
            item.enabled = value
        }
        override fun getWidth(table: javax.swing.JTable?): Int = 48
    }
}
