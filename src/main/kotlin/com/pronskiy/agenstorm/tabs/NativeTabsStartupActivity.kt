package com.pronskiy.agenstorm.tabs

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Step E1.1: runs [NativeTabsRegistryGuard] whenever a project opens (registered as `postStartupActivity`). */
class NativeTabsStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        NativeTabsRegistryGuard().sync(project)
    }
}
