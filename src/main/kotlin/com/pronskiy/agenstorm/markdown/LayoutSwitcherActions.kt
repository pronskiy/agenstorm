package com.pronskiy.agenstorm.markdown

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.vfs.VirtualFile
import com.pronskiy.agenstorm.core.ActionSlot
import com.pronskiy.agenstorm.core.AgenstormPlugin
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import org.intellij.plugins.markdown.lang.MarkdownFileType

/**
 * Epic M. Hides the editor/preview layout buttons in Markdown editors by taking the three platform actions they are
 * made of, not by touching the component that draws them.
 *
 * **Which component draws them depends on the IDE's settings**, which is what the first attempt got wrong.
 * `TextEditorWithPreview.isShowActionsInTabs()` is `NewUI && UISettings.editorTabPlacement != 0`, so with editor tabs
 * visible — the ordinary case — the buttons are rendered as *tab actions* and the floating toolbar's group is wrapped
 * in `ConditionalActionGroup { !isShowActionsInTabs() }`, leaving it empty. Detaching that toolbar therefore did
 * nothing at all. Decision 50.
 *
 * Both paths read the same three ids through `createViewActionGroup()`, so replacing the actions covers the tab, the
 * floating pill, and anywhere else they are shown. The slots are given back when the feature is switched off and
 * before the plugin unloads, so the IDE keeps its own actions.
 */
object LayoutSwitcherInstaller {

    /** Declared in `idea/PlatformActions.xml` under the group `TextEditorWithPreview.LayoutGroup`. */
    val ACTION_IDS: List<String> = listOf(
        "TextEditorWithPreview.Layout.EditorOnly",
        "TextEditorWithPreview.Layout.EditorAndPreview",
        "TextEditorWithPreview.Layout.PreviewOnly",
    )

    private val slots = ACTION_IDS.map { id -> ActionSlot(id) { HiddenInMarkdownToggleAction(stockAction(id)) } }

    /** The action currently in the slot, which is the platform's own until a slot is taken. */
    private fun stockAction(id: String): AnAction? =
        com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(id)

    fun sync() {
        val on = AgenstormSettings.getInstance().state.markdownHideLayoutSwitcher
        for (slot in slots) slot.sync(on)
    }

    fun uninstall() {
        for (slot in slots) slot.giveBack()
    }

    fun isInstalled(): Boolean = slots.all { it.isTaken() }
}

/**
 * The platform's layout toggle, invisible while the file is Markdown and untouched otherwise — so every other split
 * editor (`.editorconfig`, Swagger, Mermaid, the PHP eval scratch) keeps its buttons.
 *
 * A `ToggleAction` on purpose, and not merely for manners: `TextEditorWithPreview.getShowEditorAction()` does
 * `ActionUtil.getAction(id) as ToggleAction` with a hard null check, so a plain `AnAction` here would throw while
 * every split editor in the IDE was being built.
 */
class HiddenInMarkdownToggleAction(private val delegate: AnAction?) : ToggleAction() {

    init {
        delegate?.let { templatePresentation.copyFrom(it.templatePresentation, null, true) }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = delegate?.actionUpdateThread ?: ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = (delegate as? ToggleAction)?.isSelected(e) ?: false

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        (delegate as? ToggleAction)?.setSelected(e, state)
    }

    override fun update(e: AnActionEvent) {
        if (delegate == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        delegate.update(e)
        if (isMarkdown(fileOf(e))) e.presentation.isEnabledAndVisible = false
    }

    override fun actionPerformed(e: AnActionEvent) {
        delegate?.actionPerformed(e)
    }

    companion object {
        /**
         * The file behind this event. A tab action is raised with the editor in the data context and a toolbar action
         * with the file, so both are asked for.
         */
        fun fileOf(e: AnActionEvent): VirtualFile? =
            e.getData(PlatformCoreDataKeys.FILE_EDITOR)?.file ?: e.getData(CommonDataKeys.VIRTUAL_FILE)

        fun isMarkdown(file: VirtualFile?): Boolean =
            file != null && FileTypeRegistry.getInstance().isFileOfType(file, MarkdownFileType.INSTANCE)
    }
}

/**
 * Registered in `agenstorm-markdown.xml`. Takes the slots as the IDE starts, and again whenever the settings page
 * applies — the page lives in `core/`, which must not load this feature, so it speaks through [AgenstormSettingsListener].
 */
class LayoutSwitcherLifecycleListener : AppLifecycleListener {

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { LayoutSwitcherInstaller.sync() })
        LayoutSwitcherInstaller.sync()
    }
}

/** Registered in `agenstorm-markdown.xml`. Gives the three slots back before Agenstorm's classes go. */
class LayoutSwitcherUnloadListener : DynamicPluginListener {

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (AgenstormPlugin.isOurs(pluginDescriptor)) LayoutSwitcherInstaller.uninstall()
    }
}
