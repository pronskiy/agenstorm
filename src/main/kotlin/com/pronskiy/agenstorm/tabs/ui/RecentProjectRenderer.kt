package com.pronskiy.agenstorm.tabs.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.RecentProjectsManagerBase
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
import com.pronskiy.agenstorm.core.ProjectNameProvider
import com.pronskiy.agenstorm.tabs.ProjectNameUniquifier
import java.awt.Component
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/**
 * The recent projects of the "+" popup ([AddProjectPopup]), one line each: the project's icon, its name and the branch
 * it is on. The name and the icon are the ones the tabs show ([ProjectNameProvider]): a linked worktree reads
 * `app:fix-login` under its main checkout's icon, not its bare folder name under its own initials (Roman, 2026-10-22).
 * Two projects still sharing a name carry their parent folder in front ([ProjectNameUniquifier]) — the whole list is
 * given to tell one from the other — and the full path shows as the name's tooltip.
 *
 * The branch is asked of [branchOf] on every paint rather than taken from the action. `ReopenProjectAction` keeps the
 * branch it was made with, and Git looks a project's branch up in the background the first time anyone asks, so the
 * actions of a first popup had none and only the next popup showed them (Roman, 2026-10-19).
 */
class RecentProjectRenderer(private val actions: List<ReopenProjectAction>, private val branchOf: (String) -> String?) : ListCellRenderer<ActionItem> {

    /** Shown names, keyed by project path: a provider's name or the platform's, disambiguated across the whole list. */
    private val names: Map<String, String> = run {
        val entries = actions.map { ProjectNameUniquifier.Entry(it.projectPath, rawName(it), it.projectPath) }
        if (AgenstormSettings.getInstance().state.tabsDisambiguateNames) ProjectNameUniquifier.displayNames(entries)
        else entries.associate { it.key to it.name }
    }

    /** The name to show for [action]: the disambiguated one, or the provider's or platform's for one not in the list. */
    fun nameFor(action: ReopenProjectAction): String = names[action.projectPath] ?: rawName(action)

    /** The folder whose project icon [action]'s row shows: its own, or its main checkout's for a linked worktree. */
    internal fun iconPathFor(action: ReopenProjectAction): String = ProjectNameProvider.iconPathOf(action.projectPath) ?: action.projectPath

    override fun getListCellRendererComponent(list: JList<out ActionItem>?, value: ActionItem, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
        val action = value.action as ReopenProjectAction
        return pane(action, branchOf(action.projectPath) ?: action.branchName, isSelected, nameFor(action), iconFor(action))
    }

    /** The lookup [ReopenProjectAction.getProjectIcon] makes for its own folder, made for [iconPathFor]'s. */
    private fun iconFor(action: ReopenProjectAction): Icon {
        val path = iconPathFor(action)
        return if (path == action.projectPath) action.projectIcon else RecentProjectsManagerBase.getInstanceEx().getProjectIcon(path, true, 20, null)
    }

    private fun rawName(action: ReopenProjectAction): String = ProjectNameProvider.nameOf(action.projectPath) ?: action.projectNameToDisplay

    companion object {
        /** One project on one line: icon and name, then the branch when it is known. */
        fun pane(
            action: ReopenProjectAction,
            branch: String?,
            isSelected: Boolean,
            name: String = action.projectNameToDisplay,
            projectIcon: Icon = action.projectIcon,
        ): JComponent {
            val row = JPanel(HorizontalLayout(JBUI.scale(GAP))).apply { isOpaque = false }
            // The platform's own icon for a recent project: deferred, so the list is repainted once it is made. A first
            // lookup through the tab's way returns one that stays blank in a popup row.
            val small = IconUtil.downscaleIconToSize(projectIcon, JBUI.scale(ICON_SIZE), JBUI.scale(ICON_SIZE))
            row.add(JBLabel(name, small, SwingConstants.LEFT).apply {
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
