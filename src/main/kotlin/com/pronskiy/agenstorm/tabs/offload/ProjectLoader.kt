package com.pronskiy.agenstorm.tabs.offload

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormNotifications
import com.pronskiy.agenstorm.tabs.ProjectTab
import com.pronskiy.agenstorm.tabs.ProjectTabsModel
import java.nio.file.Files
import java.nio.file.Path

/**
 * Step P2.6. A click on an offloaded tab: open the project from its path, in a new frame always — with
 * `forceOpenInNewFrame` the platform never asks "this window or a new one?", and on macOS the new frame joins
 * the tab group like any other. The tab turns solid on its own once the project reports itself opened
 * ([ProjectTabsModel.projectOpened]). A directory that is gone forgets the tab; an open that returns nothing
 * (a declined trust dialog, say) keeps it so the click can be repeated. Both say so in a balloon.
 */
class ProjectLoader(
    private val model: ProjectTabsModel,
    private val open: suspend (Path) -> Project? = { ProjectUtil.openOrImportAsync(it, OpenProjectTask.build().withForceOpenInNewFrame(true)) },
    private val exists: (Path) -> Boolean = Files::isDirectory,
    private val notify: (String) -> Unit = ::balloon,
) {

    suspend fun load(tab: ProjectTab.Offloaded): Project? {
        val path = Path.of(tab.key)
        if (!exists(path)) {
            LOG.info("${tab.name} is gone from ${tab.key}; forgetting its tab")
            model.forget(tab.key)
            notify(AgenstormBundle.message("tabs.offload.load.missing", tab.name, tab.key))
            return null
        }
        val project = open(path)
        if (project == null) {
            LOG.warn("Opening ${tab.name} from ${tab.key} returned nothing; its tab stays")
            model.refresh()
            notify(AgenstormBundle.message("tabs.offload.load.failed", tab.name))
        }
        return project
    }

    private companion object {
        val LOG = logger<ProjectLoader>()

        fun balloon(content: String) {
            AgenstormNotifications.group()
                .createNotification(AgenstormBundle.message("tabs.offload.notice.title"), content, NotificationType.WARNING)
                .notify(null)
        }
    }
}
