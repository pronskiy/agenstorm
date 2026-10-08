package com.pronskiy.agenstorm.terminal.agents

import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import java.lang.reflect.Proxy

/** A Terminal tab that is only ever compared, never used: the place a row points at. */
internal fun fakeTab(project: Project, name: String = "tab"): ProjectTab {
    val tab = Proxy.newProxyInstance(TerminalToolWindowTab::class.java.classLoader, arrayOf(TerminalToolWindowTab::class.java)) { proxy, method, args ->
        when (method.name) {
            "toString" -> "fake tab $name"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            else -> throw UnsupportedOperationException(method.name)
        }
    } as TerminalToolWindowTab
    return ProjectTab(project, tab)
}
