package com.pronskiy.agenstorm.tabs.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.ReopenProjectAction
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.popup.PopupFactoryImpl.ActionItem
import com.intellij.ui.popup.list.SelectablePanel
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.ProjectNameUniquifier
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/**
 * The recent projects of the "+" popup ([AddProjectPopup]), one line each: the project's icon, its name and the branch
 * it is on. Two projects sharing a name carry their parent folder in front ([ProjectNameUniquifier]) — the whole list
 * is given to tell one from the other — and the full path shows as the name's tooltip.
 *
 * The branch is asked of [branchOf] on every paint rather than taken from the action. `ReopenProjectAction` keeps the
 * branch it was made with, and Git looks a project's branch up in the background the first time anyone asks, so the
 * actions of a first popup had none and only the next popup showed them (Roman, 2026-10-19).
 */
class RecentProjectRenderer(private val actions: List<ReopenProjectAction>, private val branchOf: (String) -> String?) : ListCellRenderer<ActionItem> {

    /** Shown names, disambiguated across the whole list, keyed by project path. */
    private val names: Map<String, String> =
        if (AgenstormSettings.getInstance().state.tabsDisambiguateNames)
            ProjectNameUniquifier.displayNames(actions.map { ProjectNameUniquifier.Entry(it.projectPath, it.projectNameToDisplay, it.projectPath) })
        else emptyMap()

    /** The name to show for [action]: the disambiguated one, or the platform's when the list cannot tell them apart. */
    fun nameFor(action: ReopenProjectAction): String = names[action.projectPath] ?: action.projectNameToDisplay

    override fun getListCellRendererComponent(list: JList<out ActionItem>?, value: ActionItem, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
        val action = value.action as ReopenProjectAction
        return pane(action, branchOf(action.projectPath) ?: action.branchName, isSelected, nameFor(action))
    }

    companion object {
        /** One project on one line: icon and name, then the branch when it is known. */
        fun pane(action: ReopenProjectAction, branch: String?, isSelected: Boolean, name: String = action.projectNameToDisplay): JComponent {
            val row = JPanel(HorizontalLayout(JBUI.scale(GAP))).apply { isOpaque = false }
            // The platform's own icon for a recent project: deferred, so the list is repainted once it is made. A first
            // lookup through the tab's way returns one that stays blank in a popup row.
            val projectIcon = IconUtil.downscaleIconToSize(action.projectIcon, JBUI.scale(ICON_SIZE), JBUI.scale(ICON_SIZE))
            row.add(JBLabel(name, projectIcon, SwingConstants.LEFT).apply {
                iconTextGap = JBUI.scale(6)
                toolTipText = action.projectPath
                foreground = if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()
            })
            branch?.let { row.add(detail(it).apply { icon = IconUtil.colorize(AllIcons.Vcs.Branch, UIUtil.getLabelInfoForeground()) }) }
            return SelectablePanel.wrap(row, JBUI.CurrentTheme.Popup.BACKGROUND).apply {
                PopupUtil.configListRendererFixedHeight(this)
                if (isSelected) selectionColor = UIUtil.getListSelectionBackground(true)
            }
        }

        private fun detail(text: String) = JBLabel(text).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelInfoForeground()
        }

        /** Between the name and the branch. */
        private const val GAP = 10

        /** The size of the popup's other icons, rather than the recent-projects list's 20. */
        private const val ICON_SIZE = 16
    }
}
