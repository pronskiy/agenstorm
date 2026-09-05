package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.startup.ProjectActivity

/**
 * Steps E1.1/E1.2: for every opened project, aligns the native-tabs registry key ([NativeTabsRegistryGuard]) and
 * adds the project to [ProjectTabsModel]. Registered as `postStartupActivity`.
 */
class TabsStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        NativeTabsRegistryGuard().sync(project)
        ProjectTabsModel.getInstance().projectOpened(project)
    }
}

/** Step E1.2: removes closed projects from the tab strip (declared under `applicationListeners`). */
class TabsProjectCloseListener : ProjectCloseListener {

    override fun projectClosed(project: Project) {
        ProjectTabsModel.getInstance().projectClosed(project)
    }
}
