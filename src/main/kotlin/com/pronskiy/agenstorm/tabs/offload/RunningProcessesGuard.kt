package com.pronskiy.agenstorm.tabs.offload

import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.project.Project
import com.pronskiy.agenstorm.core.AgenstormBundle

/**
 * Step P2.4. A live run/debug process keeps its project loaded: closing the project would have the Run tool
 * window ask whether to terminate it. `ExecutionManager.getRunningProcesses` is public; a handler that has
 * already terminated does not count. [processes] is replaceable for the tests.
 */
class RunningProcessesGuard(
    private val processes: (Project) -> List<ProcessHandler> = { ExecutionManager.getInstance(it).getRunningProcesses().toList() },
) : OffloadGuard {

    override fun busyReason(project: Project): String? =
        if (processes(project).any { !it.isProcessTerminated }) AgenstormBundle.message("tabs.offload.busy.process") else null
}
