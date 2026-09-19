package com.pronskiy.agenstorm.tabs.offload

import com.intellij.execution.process.NopProcessHandler
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.lang.reflect.Proxy

/** Step P2.4: the guards are the whole reason no "terminate?" dialog can ever appear for a background project. */
class OffloadGuardTest : BasePlatformTestCase() {

    fun testBothGuardsAreRegisteredAndAnIdleProjectIsNotBusy() {
        val guards = OffloadGuard.EP_NAME.extensionList
        assertTrue(guards.any { it is RunningProcessesGuard })
        assertTrue("the terminal plugin is bundled in the test IDE", guards.any { it is TerminalCommandGuard })
        assertNull(OffloadGuard.busyReason(project))
    }

    fun testALiveProcessKeepsTheProjectLoadedATerminatedOneDoesNot() {
        val handler = NopProcessHandler()
        handler.startNotify()
        val guard = RunningProcessesGuard(processes = { listOf(handler) })
        assertNotNull(guard.busyReason(project))

        handler.destroyProcess()
        assertNull(guard.busyReason(project))
        assertNull(RunningProcessesGuard(processes = { emptyList() }).busyReason(project))
    }

    fun testARunningTerminalCommandKeepsTheProjectLoaded() {
        assertNotNull(TerminalCommandGuard(widgets = { listOf(widget(running = true), widget(running = false)) }).busyReason(project))
        assertNull(TerminalCommandGuard(widgets = { listOf(widget(running = false)) }).busyReason(project))
        assertNull(TerminalCommandGuard(widgets = { emptyList() }).busyReason(project))
    }

    private fun widget(running: Boolean): TerminalWidget = Proxy.newProxyInstance(
        TerminalWidget::class.java.classLoader,
        arrayOf(TerminalWidget::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "isCommandRunning" -> running
            else -> throw UnsupportedOperationException(method.name)
        }
    } as TerminalWidget
}
