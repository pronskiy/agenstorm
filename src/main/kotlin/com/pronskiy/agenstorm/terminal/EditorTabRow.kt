package com.pronskiy.agenstorm.terminal

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.ComponentUtil
import com.intellij.ui.InplaceButton
import com.intellij.ui.tabs.JBTabs
import java.awt.Component
import javax.swing.AbstractButton
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Steps J1.8 and J1.9. The row of editor tabs a maximized terminal leaves showing above it.
 *
 * `setMaximized` stretches the terminal until the editor's area is down to the pane splitter's minimum, the
 * `ide.mainSplitter.min.size` registry value, and that is a few pixels shorter than a tab row: the terminal's
 * title bar sat over the bottom of the tabs. So the tab row is measured once the pane has its maximized shape,
 * and the terminal is shrunk by what it covers — `ToolWindowEx.stretchHeight` changes its height by a delta and
 * leaves the maximized state, and so the height un-maximizing restores, alone.
 */
object EditorTabRow {

    /** Makes the whole top tab row show above a maximized [terminal]. EDT; tabs placed elsewhere are left as they are. */
    fun uncover(project: Project, terminal: ToolWindow) {
        if (UISettings.getInstance().editorTabPlacement != SwingConstants.TOP) return
        // Lay the pane out at its maximized size now, or the tabs would be measured where they were before.
        WindowManager.getInstance().getFrame(project)?.validate() ?: return
        val tabs = topTabs(project) ?: return
        val hidden = coveredHeaderHeight(tabs)
        if (hidden > 0) (terminal as? ToolWindowEx)?.stretchHeight(-hidden)
    }

    /** The project's topmost editor tabs on screen: with the editor split one above the other, the upper one. */
    fun topTabs(project: Project): JBTabs? =
        FileEditorManager.getInstance(project).selectedEditors.asSequence()
            .mapNotNull { ComponentUtil.getParentOfType(JBTabs::class.java, it.component) }
            .filter { it.component.isShowing }
            .distinct()
            .minByOrNull { it.component.locationOnScreen.y }

    /**
     * The pixels of [tabs]' header — the tab row down to where the editor starts, the line under the tabs
     * included — that fall below the part of [tabs] on screen.
     */
    fun coveredHeaderHeight(tabs: JBTabs): Int {
        val root = tabs.component
        var headerBottom = 0
        tabs.selectedInfo?.component?.let { content ->
            if (SwingUtilities.isDescendingFrom(content, root)) {
                headerBottom = SwingUtilities.convertPoint(content.parent, content.x, content.y, root).y
            }
        }
        for (info in tabs.tabs) {
            val label = tabs.getTabLabel(info) ?: continue
            if (!label.isVisible || label.height == 0 || !SwingUtilities.isDescendingFrom(label, root)) continue
            headerBottom = maxOf(headerBottom, SwingUtilities.convertPoint(label.parent, label.x, label.y + label.height, root).y)
        }
        val visible = root.visibleRect
        return covered(headerBottom, visible.y + visible.height)
    }

    fun covered(headerBottom: Int, visibleBottom: Int): Int = (headerBottom - visibleBottom).coerceAtLeast(0)

    /** True when [target] is on one of the tab labels of [project]'s editors — the label, not a button on it. */
    fun isEditorTabClick(project: Project, target: Component?): Boolean {
        target ?: return false
        val tabs = ComponentUtil.getParentOfType(JBTabs::class.java, target) ?: return false
        // Every window's clicks reach the listener; only this project's editor tabs count.
        val editors = FileEditorManager.getInstance(project).selectedEditors
        if (editors.none { SwingUtilities.isDescendingFrom(it.component, tabs.component) }) return false
        return isTabLabelClick(target, tabs)
    }

    /** True when [target] is inside a tab label of [tabs], and not on a button there such as the close cross. */
    fun isTabLabelClick(target: Component, tabs: JBTabs): Boolean {
        val label = tabs.tabs.firstNotNullOfOrNull { info ->
            tabs.getTabLabel(info)?.takeIf { SwingUtilities.isDescendingFrom(target, it) }
        } ?: return false
        var c: Component? = target
        while (c != null && c !== label) {
            if (c is InplaceButton || c is ActionButton || c is AbstractButton) return false
            c = c.parent
        }
        return true
    }
}
