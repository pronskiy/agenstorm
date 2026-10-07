package com.pronskiy.agenstorm.markdown.toc

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import javax.swing.JComponent

/**
 * Step W2.5. The one action of [TocFloatingProvider]'s toolbar: no button of its own, only [TocPanel] as its custom
 * component. Visible while the editor's [TocController] says the widget shows; the BGT update reads that cached state
 * and nothing else, because the toolbar re-runs it on its own timer. Made in code and never registered, so it stays
 * out of Find Action and the keymap.
 */
class TocAction : AnAction(), CustomComponentAction, DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        val controller = if (editor == null || project == null || project.isDisposed) null
        else project.serviceIfCreated<TocService>()?.controllerFor(editor)
        e.presentation.isEnabledAndVisible = controller?.state?.shows == true
        e.presentation.putClientProperty(CONTROLLER, controller)
    }

    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent = TocPanel()

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        (component as? TocPanel)?.bind(presentation.getClientProperty(CONTROLLER))
    }

    companion object {
        /** The editor's controller, handed from the BGT update to the panel on the EDT. */
        val CONTROLLER: Key<TocController> = Key.create("agenstorm.toc.controller")
    }
}
