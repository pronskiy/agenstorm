package com.pronskiy.agenstorm.toolwindows

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Epic N. The tool windows Agenstorm moved off the right side in this project, so it can put back exactly those and
 * no others.
 *
 * Stored in the project's workspace file rather than in `agenstorm.xml`: an anchor is itself persisted per project in
 * the tool window layout, which lives there too, and this record is worthless without it. Nothing lands in the shared
 * `.idea` config.
 */
@Service(Service.Level.PROJECT)
@State(name = "AgenstormToolWindows", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class MovedToolWindows : PersistentStateComponent<MovedToolWindows.State> {

    class State {
        var movedOffTheRight: MutableList<String> = mutableListOf()
    }

    private var currentState = State()

    override fun getState(): State = currentState

    override fun loadState(state: State) {
        currentState = state
    }

    var ids: Set<String>
        get() = currentState.movedOffTheRight.toSet()
        set(value) {
            currentState.movedOffTheRight = value.sorted().toMutableList()
        }

    companion object {
        fun getInstance(project: Project): MovedToolWindows = project.service()
    }
}
