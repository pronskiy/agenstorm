package com.pronskiy.agenstorm.tabs.offload

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormNotifications
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.tabs.ProjectTab
import com.pronskiy.agenstorm.tabs.ProjectTabsModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Steps P2.5/P2.6. Runs [Offloader] on the real IDE — once a minute, and shortly after every project open (the
 * cap) — and [ProjectLoader] for a click on an offloaded tab.
 * Every sweep happens on the EDT under the non-modal modality state — `closeAndDispose` wants the EDT outside a
 * write action, and a close while a dialog is up is not something to do behind the user's back. The active
 * project is the one whose frame was focused last, which also protects the last-used project while the IDE is
 * in the background.
 */
@Service(Service.Level.APP)
class ProjectOffloadService(private val scope: CoroutineScope) {

    private val started = AtomicBoolean(false)
    private val loader = ProjectLoader(
        model = ProjectTabsModel.getInstance(),
        close = { project ->
            withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) { ProjectManager.getInstance().closeAndDispose(project) }
        },
    )

    private val offloader = Offloader(
        model = ProjectTabsModel.getInstance(),
        settings = {
            val state = AgenstormSettings.getInstance().state
            OffloadSettings(state.projectsOffloadEnabled, state.projectsOffloadAfterMinutes * 60_000L, state.projectsMaxLoaded)
        },
        activeKey = { IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project?.takeUnless { it.isDisposed }?.let(ProjectTabsModel::keyOf) },
        busyReason = OffloadGuard::busyReason,
        close = { ProjectManager.getInstance().closeAndDispose(it) },
        clock = System::currentTimeMillis,
        onOffloaded = OffloadNotice::showOnce,
    )

    /** For every project that opens: the periodic sweep starts once, and the cap is checked once the new frame has settled. */
    fun projectOpened() {
        if (started.compareAndSet(false, true)) {
            scope.launch {
                while (isActive) {
                    delay(SWEEP_INTERVAL)
                    sweep()
                }
            }
        }
        scope.launch {
            delay(CAP_DELAY)
            sweep()
        }
    }

    /** Re-runs the rule now, e.g. after the settings changed. */
    fun sweepSoon() {
        scope.launch { sweep() }
    }

    /** A click on an offloaded tab (P2.6). */
    fun load(tab: ProjectTab.Offloaded) {
        scope.launch { loader.load(tab) }
    }

    /** The frame's own project is closed with only bookmarks left: load [tab], then close [toClose]. */
    fun loadThenClose(tab: ProjectTab.Offloaded, toClose: Project) {
        scope.launch { loader.loadThenClose(tab, toClose) }
    }

    /** Offload Project from the context menu (P2.7): closes now, or says why the guards object. */
    fun offloadNow(project: Project) {
        scope.launch {
            val busy = withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
                if (project.isDisposed) null else offloader.offload(project)
            }
            if (busy != null) {
                AgenstormNotifications.group()
                    .createNotification(
                        AgenstormBundle.message("tabs.offload.notice.title"),
                        AgenstormBundle.message("tabs.offload.manual.busy", project.name, busy),
                        NotificationType.INFORMATION,
                    )
                    .notify(null)
            }
        }
    }

    suspend fun sweep(): List<String> = withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
        offloader.sweep(ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault })
    }

    companion object {
        val SWEEP_INTERVAL = 60.seconds
        val CAP_DELAY = 5.seconds

        fun getInstance(): ProjectOffloadService = ApplicationManager.getApplication().getService(ProjectOffloadService::class.java)
    }
}
