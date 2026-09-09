package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Steps E1.1/E1.2/E1.6: for every opened project, gives the macOS window tabs back if Agenstorm 1.0 took them
 * away ([NativeTabsRegistryGuard]), adds the project to [ProjectTabsModel], and hides the platform's tab row on
 * this frame ([NativeTabStrip]) so the header stays a single line. Registered as `postStartupActivity`.
 */
class TabsStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Normally the app lifecycle listener has the toolbar slot already; this covers a plugin installed
        // into a running IDE, where there was no frame-created event to catch.
        ProjectTabsWidgetInstaller.sync()
        NativeTabsRegistryGuard().sync(project)
        ProjectTabsModel.getInstance().projectOpened(project)
        withContext(Dispatchers.EDT) {
            if (!project.isDisposed) NativeTabStrip.install(project)
        }
    }
}

/** Step E1.2: removes closed projects from the tab strip (declared under `applicationListeners`). */
class TabsProjectCloseListener : ProjectCloseListener {

    override fun projectClosed(project: Project) {
        ProjectTabsModel.getInstance().projectClosed(project)
    }
}
