package com.pronskiy.agenstorm.terminal.agents

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Step X1.3. The one layout every window's Agents sidebar shows (decision 102): open or not, how wide, on which side,
 * which row is selected, which groups are folded and how far it is scrolled. Recorded from the window in front, applied
 * to all the others at once (decision 105), and kept across restarts so a project opened later takes it too.
 */
@Service(Service.Level.APP)
@State(name = "AgenstormAgentsSidebar", storages = [Storage("agenstorm-agents.xml")])
class AgentsSidebarState : PersistentStateComponent<AgentsSidebarState.Layout> {

    /**
     * [width] 0 and [anchor] "" mean not recorded yet; [selected] is a row's id, "" for none; [collapsed] holds the base
     * paths of folded groups, so a project that joins starts unfolded.
     */
    data class Layout(
        var visible: Boolean = false,
        var width: Int = 0,
        var anchor: String = "",
        var selected: String = "",
        var collapsed: MutableList<String> = mutableListOf(),
        var scroll: Int = 0,
    )

    private val flow = MutableStateFlow(Layout())

    /** Snapshots: a new [Layout] after every change; never mutate one. */
    val layout: StateFlow<Layout> = flow.asStateFlow()

    override fun getState(): Layout = flow.value

    override fun loadState(state: Layout) {
        flow.value = state.copy(collapsed = state.collapsed.toMutableList())
    }

    /** Replaces the layout with [change] applied to a copy; returns whether anything changed. Any thread. */
    fun update(change: (Layout) -> Layout): Boolean {
        while (true) {
            val before = flow.value
            val after = change(before.copy(collapsed = before.collapsed.toMutableList()))
            if (after == before) return false
            if (flow.compareAndSet(before, after)) return true
        }
    }

    companion object {
        fun getInstance(): AgentsSidebarState = service()
    }
}
