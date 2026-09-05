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
 * Step E1.2. The ordered list of open projects that the toolbar tab strip renders, shared by every frame.
 * The order is remembered by project base path in `agenstorm-tabs.xml`, so a project reopened later returns to
 * its place; projects the model has never seen are appended in the order the IDE lists them. Fed by
 * [TabsStartupActivity] (project opened) and [TabsProjectCloseListener] (project closed). Listeners are always
 * called on the EDT.
 */
@Service(Service.Level.APP)
@State(name = "AgenstormProjectTabs", storages = [Storage("agenstorm-tabs.xml")])
class ProjectTabsModel : PersistentStateComponent<ProjectTabsModel.State> {

    class State {
        /** Tab order as project keys (base paths); closed projects stay remembered up to [MAX_REMEMBERED]. */
        var order: MutableList<String> = mutableListOf()
    }

    fun interface Listener {
        fun tabsChanged(tabs: List<Project>)
    }

    private var state = State()
    private val listeners = CopyOnWriteArrayList<Listener>()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** Open projects in tab order. */
    fun tabs(): List<Project> {
        val byKey = openProjects().associateBy(::keyOf)
        return sortKeys(byKey.keys).mapNotNull(byKey::get)
    }

    /** Moves [project] to [index] among the open tabs and persists the new order. */
    fun moveTab(project: Project, index: Int) {
        moveKey(keyOf(project), index, openProjects().map(::keyOf))
        fire()
    }

    fun addListener(listener: Listener, parentDisposable: Disposable) {
        listeners.add(listener)
        Disposer.register(parentDisposable) { listeners.remove(listener) }
    }

    fun projectOpened(project: Project) {
        remember(keyOf(project), openProjects().map(::keyOf))
        fire()
    }

    fun projectClosed(@Suppress("unused") project: Project) {
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

        fun getInstance(): ProjectTabsModel = ApplicationManager.getApplication().getService(ProjectTabsModel::class.java)

        /** Base path when the project has one (all real projects do), else the location hash. */
        fun keyOf(project: Project): String = project.basePath ?: project.locationHash
    }
}
