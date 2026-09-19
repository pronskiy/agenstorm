package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Steps E1.2 / P2.1. The ordered list of tabs the toolbar strip renders, shared by every frame: every open project
 * as [ProjectTab.Loaded], plus every project Agenstorm offloaded as [ProjectTab.Offloaded], until it is loaded
 * again or forgotten. The order is remembered by project base path in `agenstorm-tabs.xml`, so a project reopened
 * later — or offloaded and loaded again — returns to its place; projects the model has never seen are appended in
 * the order the IDE lists them. [lastActive] is when each project's window was last activated, the offload policy's
 * input. Fed by [TabsStartupActivity] (project opened), [TabsProjectCloseListener] (project closed) and the offload
 * service. Listeners are always called on the EDT.
 */
@Service(Service.Level.APP)
@State(name = "AgenstormProjectTabs", storages = [Storage("agenstorm-tabs.xml")])
class ProjectTabsModel : PersistentStateComponent<ProjectTabsModel.State> {

    class State {
        /** Tab order as project keys (base paths); closed projects stay remembered up to [MAX_REMEMBERED]. */
        var order: MutableList<String> = mutableListOf()
        /** Projects offloaded by Agenstorm, oldest first; at most [MAX_OFFLOADED]. */
        var offloaded: MutableList<OffloadedEntry> = mutableListOf()
        /** Key → epoch millis of the last frame activation (or open). */
        var lastActive: MutableMap<String, Long> = mutableMapOf()
    }

    /** A bean for the serializer; [ProjectTab.Offloaded] is what the rest of the plugin sees. */
    data class OffloadedEntry(var key: String = "", var name: String = "", var since: Long = 0L)

    fun interface Listener {
        fun tabsChanged(tabs: List<ProjectTab>)
    }

    private var state = State()
    private val listeners = CopyOnWriteArrayList<Listener>()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** Every tab, loaded and offloaded, in stored order. An offloaded key that is open after all counts as loaded. */
    fun tabs(): List<ProjectTab> {
        val loaded = openProjects().associateBy(::keyOf)
        val offloaded = state.offloaded.filter { it.key !in loaded }.associateBy { it.key }
        return sortKeys(loaded.keys + offloaded.keys).mapNotNull { key ->
            loaded[key]?.let { ProjectTab.Loaded(it) } ?: offloaded[key]?.let { ProjectTab.Offloaded(it.key, it.name, it.since) }
        }
    }

    /** The open projects in tab order — what the parts of the strip that only deal with windows use. */
    fun loadedProjects(): List<Project> = tabs().filterIsInstance<ProjectTab.Loaded>().map { it.project }

    /** Moves the tab with [key] to [index] among the tabs and persists the new order. */
    fun moveTab(key: String, index: Int) {
        moveKey(key, index, tabs().map { it.key })
        fire()
    }

    /** Keeps a tab for [project], which is about to be closed by the offload service. */
    fun markOffloaded(project: Project, now: Long = System.currentTimeMillis()) {
        val key = keyOf(project)
        remember(key, openProjects().map(::keyOf))
        if (state.offloaded.none { it.key == key }) state.offloaded.add(OffloadedEntry(key, project.name, now))
        while (state.offloaded.size > MAX_OFFLOADED) state.offloaded.removeAt(0)
        fire()
    }

    /** The close did not happen after all: the tab is an ordinary one again (the project is still open). */
    fun unmarkOffloaded(key: String) {
        if (state.offloaded.removeIf { it.key == key }) fire()
    }

    /** Drops an offloaded tab; the project stays in the IDE's recent list. */
    fun forget(key: String) {
        if (state.offloaded.removeIf { it.key == key }) fire()
    }

    /** Records that [key]'s window was active at [now]. Nothing visible changes, so listeners are not called. */
    fun touch(key: String, now: Long = System.currentTimeMillis()) {
        state.lastActive[key] = now
    }

    fun lastActive(key: String): Long? = state.lastActive[key]

    fun addListener(listener: Listener, parentDisposable: Disposable) {
        listeners.add(listener)
        Disposer.register(parentDisposable) { listeners.remove(listener) }
    }

    fun projectOpened(project: Project, now: Long = System.currentTimeMillis()) {
        val key = keyOf(project)
        remember(key, openProjects().map(::keyOf))
        state.offloaded.removeIf { it.key == key }
        touch(key, now)
        fire()
    }

    fun projectClosed(@Suppress("unused") project: Project) {
        fire()
    }

    /** Re-renders every strip, e.g. after the tab settings changed. */
    fun refresh() {
        fire()
    }

    /** Stored order first (open keys only), then the keys the model has not seen, in the given order. */
    internal fun sortKeys(openKeys: Collection<String>): List<String> =
        state.order.filter { it in openKeys } + openKeys.filter { it !in state.order }

    /** Appends an unseen key; forgets the oldest closed keys once the list outgrows [MAX_REMEMBERED]. */
    internal fun remember(key: String, openKeys: Collection<String>) {
        if (key !in state.order) state.order.add(key)
        while (state.order.size > MAX_REMEMBERED) {
            val closed = state.order.firstOrNull { it !in openKeys && it != key } ?: break
            state.order.remove(closed)
        }
        state.lastActive.keys.retainAll { it in state.order }
    }

    /** New order = open tabs with [key] at [index], followed by the remembered closed keys. */
    internal fun moveKey(key: String, index: Int, openKeys: Collection<String>) {
        val open = sortKeys(openKeys).toMutableList()
        if (!open.remove(key)) return
        open.add(index.coerceIn(0, open.size), key)
        state.order = (open + state.order.filter { it !in open }).toMutableList()
    }

    private fun openProjects(): List<Project> =
        ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault }

    private fun fire() {
        val application = ApplicationManager.getApplication()
        val notify = Runnable {
            val tabs = tabs()
            for (listener in listeners) listener.tabsChanged(tabs)
        }
        if (application.isDispatchThread) notify.run() else application.invokeLater(notify, ModalityState.any())
    }

    companion object {
        const val MAX_REMEMBERED = 100
        /** Offloaded tabs are bookmarks; beyond this the oldest is forgotten so the icon strip always fits (decision 65). */
        const val MAX_OFFLOADED = 12

        fun getInstance(): ProjectTabsModel = ApplicationManager.getApplication().getService(ProjectTabsModel::class.java)

        /** Base path when the project has one (all real projects do), else the location hash. */
        fun keyOf(project: Project): String = project.basePath ?: project.locationHash
    }
}
