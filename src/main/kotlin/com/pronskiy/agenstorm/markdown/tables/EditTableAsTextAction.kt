package com.pronskiy.agenstorm.markdown.tables

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.DumbAware
import com.pronskiy.agenstorm.markdown.LiveMarkupService
import com.pronskiy.agenstorm.markdown.ToggleLiveMarkupAction

/**
 * Step Q3.4. The escape hatch for structural edits — rows, columns, anything a single cell cannot hold: reveals
 * the rendered table under the context-menu point, or without a point the first rendered table at or after the
 * caret, by moving the caret into its first cell; the reveal rule (decision 67) does the rest. Shown only while
 * the editor has a rendered table, so it never clutters the menu of a plain Markdown file.
 */
class EditTableAsTextAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val controller = controllerOf(e)
        e.presentation.isEnabledAndVisible = controller != null && controller.tableInlays().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val controller = controllerOf(e) ?: return
        val point = e.getData(PlatformDataKeys.CONTEXT_MENU_POINT)
        val editor = ToggleLiveMarkupAction.editorOf(e) ?: return
        val inlays = controller.tableInlays()
        val target = point?.let { controller.tableAt(it) }
            ?: inlays.firstOrNull { it.offset >= editor.caretModel.offset }
            ?: inlays.firstOrNull()
            ?: return
        controller.revealTable(target)
    }

    private fun controllerOf(e: AnActionEvent) = e.project?.let { project ->
        ToggleLiveMarkupAction.editorOf(e)?.let { LiveMarkupService.getInstance(project).controllerFor(it) }
    }
}
