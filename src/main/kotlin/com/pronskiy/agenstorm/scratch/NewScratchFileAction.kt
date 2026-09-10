package com.pronskiy.agenstorm.scratch

import com.intellij.ide.scratch.ScratchFileCreationHelper
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.lang.Language
import com.intellij.lang.LanguageUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.SimpleListCellRenderer
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormSettings
import javax.swing.Icon
import javax.swing.JList

/**
 * Step B2.2. The New Scratch File popup, trimmed to the languages the allow-list names.
 *
 * [ScratchActionInstaller] swaps this into the platform's `NewScratchFile` slot while the feature is on, so it
 * inherits that action's keyboard shortcut and its places in the File → New and editor popup menus, and the
 * platform's own action comes back the moment the feature goes off.
 *
 * What it reproduces from the platform's popup: the language list with file-type icons and speed search, the
 * creation helper's template for the chosen language (so a PHP scratch still opens with `<?php`), an editor
 * selection as the new file's text, and the caret where the template asks for it. What it does not: the
 * recent-first ordering — with a handful of allowed languages there is nothing to rank — and selections taken
 * from trees, tables and the terminal, whose extractors the platform keeps to itself.
 */
class NewScratchFileAction : AnAction(), DumbAware {

    init {
        templatePresentation.text = AgenstormBundle.message("scratch.newFile.text")
        templatePresentation.description = AgenstormBundle.message("scratch.newFile.description")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText
        showPopup(project, e.dataContext, allowed(), offerAllLanguages = true, selection = selection)
    }

    /**
     * The allow-listed languages, or every language when the list names none of them — a popup with nothing in
     * it would be a dead end, and an empty allow-list is a settings mistake, not a request for no scratch files.
     */
    private fun allowed(): List<Language> {
        val all = candidates()
        val allowed = ScratchLanguageAllowList.filter(all, AgenstormSettings.getInstance().state.scratchAllowedLanguages)
        return allowed.ifEmpty { all }
    }

    private fun candidates(): List<Language> = LanguageUtil.getFileLanguages()

    private fun showPopup(
        project: Project,
        dataContext: DataContext,
        languages: List<Language>,
        offerAllLanguages: Boolean,
        selection: String?,
    ) {
        val items = languages.map(Item::Choice) + if (offerAllLanguages) listOf(Item.AllLanguages) else emptyList()
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle(AgenstormBundle.message("scratch.newFile.popup.title"))
            .setRenderer(ItemRenderer())
            .setNamerForFiltering { it.text }
            .setItemChosenCallback { item ->
                when (item) {
                    is Item.Choice -> create(project, dataContext, item.language, selection)
                    // The way out of the filter: the same popup again, with everything in it and no way back in.
                    Item.AllLanguages -> showPopup(
                        project,
                        dataContext,
                        candidates().sortedWith(LanguageUtil.LANGUAGE_COMPARATOR),
                        offerAllLanguages = false,
                        selection = selection,
                    )
                }
            }
            .createPopup()
            .showInBestPositionFor(dataContext)
    }

    /**
     * Creates the scratch and opens it. Runs on the EDT, where the platform's own action runs it:
     * `createScratchFile` takes the write action itself, and picks the next free `scratch_N` name.
     */
    private fun create(project: Project, dataContext: DataContext, language: Language, selection: String?) {
        val context = ScratchFileCreationHelper.Context()
        context.language = language
        context.text = selection.orEmpty()

        val helper = ScratchFileCreationHelper.EXTENSION.forLanguage(language)
        if (helper != null) {
            // A selection is the text; only an empty scratch gets the language's template.
            if (context.text.isEmpty()) helper.prepareText(project, context, dataContext)
            helper.beforeCreate(project, context)
        }

        val file = createFile(project, language, context) ?: return
        helper?.let { after ->
            PsiManager.getInstance(project).findFile(file)?.let { after.afterCreate(project, context, it) }
        }
        OpenFileDescriptor(project, file, context.caretOffset.coerceIn(0, context.text.length)).navigate(true)
    }

    private fun createFile(project: Project, language: Language, context: ScratchFileCreationHelper.Context): VirtualFile? {
        val extension = context.fileExtension ?: LanguageUtil.getLanguageFileType(language)?.defaultExtension.orEmpty()
        val name = if (extension.isEmpty()) FILE_PREFIX else "$FILE_PREFIX.$extension"
        return try {
            ScratchRootType.getInstance().createScratchFile(project, name, language, context.text)
        } catch (e: RuntimeException) {
            LOG.warn("Cannot create a scratch file for ${language.id}", e)
            null
        }
    }

    private class ItemRenderer : SimpleListCellRenderer<Item>() {
        override fun customize(list: JList<out Item>, value: Item?, index: Int, selected: Boolean, hasFocus: Boolean) {
            text = value?.text.orEmpty()
            icon = value?.icon
        }
    }

    /** One row of the popup: a language to create, or the way out of the filter. */
    private sealed interface Item {

        val text: String
        val icon: Icon?

        class Choice(val language: Language) : Item {
            override val text: String get() = language.displayName
            override val icon: Icon? get() = LanguageUtil.getLanguageFileType(language)?.icon
        }

        data object AllLanguages : Item {
            override val text: String get() = AgenstormBundle.message("scratch.newFile.allLanguages")
            override val icon: Icon? get() = null
        }
    }

    private companion object {
        private val LOG = logger<NewScratchFileAction>()

        /** What the platform names its scratches; the platform then makes it unique. */
        const val FILE_PREFIX = "scratch"
    }
}
