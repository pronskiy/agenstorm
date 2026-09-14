package com.pronskiy.agenstorm.projectview

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.pronskiy.agenstorm.core.ActionSlot
import com.pronskiy.agenstorm.core.AgenstormPlugin
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step O1.3. Holds the three slots the feature borrows while it is on, and gives them back when it is off.
 *
 * `NewFile` and `NewDir` are declared inside `<group id="NewGroup">` in the platform's `LangActions.xml`, which
 * is what the Alt+Insert popup, File | New and the project view's context menu all render — so taking the two
 * ids covers every way in at once, and `NewElement` itself, the popup around them, is left alone.
 *
 * `NewDir`'s stock action is `@ApiStatus.Internal`. That is exactly what [ActionSlot] is for: the displaced
 * action is held as a plain [AnAction] and its type is never named here.
 */
object ProjectViewActionInstaller {

    const val NEW_FILE_ACTION_ID: String = "NewFile"
    const val NEW_DIR_ACTION_ID: String = "NewDir"
    const val RENAME_ACTION_ID: String = "RenameElement"

    /** The group the New menu renders, and where the typed entries of Phase O3 are found. */
    const val NEW_GROUP_ID: String = "NewGroup"

    /** Deep enough for `NewGroup` → `NewWebDevelopment` → an entry, and shallow enough not to wander. */
    private const val MAX_SCAN_DEPTH = 3

    private val log = com.intellij.openapi.diagnostic.logger<ProjectViewActionInstaller>()

    /** Ids this feature already owns, so the scan never offers to take one of them twice. */
    private val OWN_IDS = setOf(NEW_FILE_ACTION_ID, NEW_DIR_ACTION_ID, "NewScratchFile")

    private val fixedSlots = listOf(
        ActionSlot(NEW_FILE_ACTION_ID) { InlineCreateAction(InlineNameKind.NEW_FILE, stockAction(NEW_FILE_ACTION_ID)) },
        ActionSlot(NEW_DIR_ACTION_ID) { InlineCreateAction(InlineNameKind.NEW_DIRECTORY, stockAction(NEW_DIR_ACTION_ID)) },
        ActionSlot(RENAME_ACTION_ID) { InlineRenameAction(stockAction(RENAME_ACTION_ID)) },
    )

    /** The typed entries, found once on the first sync — the New menu does not change after startup. */
    private var templateSlots: List<ActionSlot>? = null

    /** What the scan found, as action id to template name; empty until the first sync. Read by the tests. */
    @Volatile
    var scannedTemplateEntries: Map<String, String> = emptyMap()
        private set

    private fun slots(): List<ActionSlot> = fixedSlots + templateSlots()

    @Synchronized
    private fun templateSlots(): List<ActionSlot> =
        templateSlots ?: scanTemplateEntries().also { templateSlots = it }

    /**
     * Step O3.1. The New menu's typed entries: *PHP File*, *HTML File*, *JavaScript File* and whatever else a
     * plugin has added that follows the same convention.
     *
     * They are found by **name**, decision 59 — every one extends `CreateFileFromTemplateAction`, whose
     * `buildDialog` is `protected`, so an entry cannot be asked which template it uses. What can be relied on
     * is that an entry named *PHP File* is backed by a file template named *PHP File*; the action checks that
     * at invocation and hands the event back to its own dialog when the name resolves to nothing, so a wrong
     * guess costs a dialog rather than a broken menu item.
     *
     * Entries whose name is not `… File` are left alone on purpose: *PHP Class*, *Interface* and *Trait* carry
     * a namespace that a single field cannot hold.
     */
    private fun scanTemplateEntries(): List<ActionSlot> {
        val group = ActionManager.getInstance().getAction(NEW_GROUP_ID) as? ActionGroup ?: return emptyList()
        val found = LinkedHashMap<String, String>()
        collect(group, depth = 0, into = found)
        scannedTemplateEntries = found
        return found.map { (id, name) -> ActionSlot(id) { InlineTemplateFileAction(name, stockAction(id)) } }
    }

    /**
     * The typed entries are not direct children of `NewGroup`: JavaScript and CSS add themselves to
     * `NewWebDevelopment`, and the rest sit in their own product groups, so the scan has to walk down.
     */
    private fun collect(group: ActionGroup, depth: Int, into: MutableMap<String, String>) {
        if (depth > MAX_SCAN_DEPTH) return
        val actionManager = ActionManager.getInstance()
        // `ActionGroup.getChildren(AnActionEvent)` is @ApiStatus.OverrideOnly and the verifier says so;
        // `DefaultActionGroup.getChildren(ActionManager)` is public, final and unannotated. A group that is
        // not one is left alone rather than opened by the back door.
        val children = (group as? DefaultActionGroup)?.getChildren(actionManager) ?: return
        for (child in children) {
            if (child is ActionGroup) {
                collect(child, depth + 1, into)
                continue
            }
            val id = actionManager.getId(child) ?: continue
            if (id in OWN_IDS || id in into) continue
            val name = child.templatePresentation.text?.trim() ?: continue
            if (!name.endsWith(" File")) continue
            into[id] = name
        }
    }

    /** Whatever is in the slot right now, which is the platform's own until the slot is taken. */
    private fun stockAction(id: String): AnAction? = ActionManager.getInstance().getAction(id)

    fun sync() {
        val on = AgenstormSettings.getInstance().state.projectTreeInlineNamingEnabled
        slots().forEach { it.sync(on) }
    }

    fun install() = slots().forEach { it.take() }

    fun uninstall() = slots().forEach { it.giveBack() }

    fun isInstalled(): Boolean = fixedSlots.all { it.isTaken() }

    /** Forces the scan, for a test that wants to see what this IDE offers without taking anything. */
    fun scanForTests(): Map<String, String> {
        templateSlots()
        return scannedTemplateEntries
    }
}

/** Takes the slots as the IDE starts (declared under `applicationListeners`). */
class ProjectViewLifecycleListener : AppLifecycleListener {

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        ProjectViewActionInstaller.sync()
    }
}

/** Gives the slots back before Agenstorm is unloaded, so the IDE keeps its own New File and New Directory. */
class ProjectViewUnloadListener : DynamicPluginListener {

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (AgenstormPlugin.isOurs(pluginDescriptor)) ProjectViewActionInstaller.uninstall()
    }
}
