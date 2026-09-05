package com.pronskiy.agenstorm.tabs

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.registry.Registry
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormNotifications
import com.pronskiy.agenstorm.core.AgenstormSettings
import java.util.MissingResourceException

/**
 * Step E1.1. Keeps the native macOS project tabs (registry `ide.mac.os.wintabs.version2`, restart required) in
 * step with the "project tabs" toggle. While the feature is on, the native strip is turned off so the toolbar
 * tabs are the only ones; when the user turns the feature off, the key is restored — but only if Agenstorm was
 * the one that changed it ([AgenstormSettings.State.nativeTabsDisabledByAgenstorm]), so a user who had disabled
 * native tabs themselves keeps that choice. Off macOS there is nothing to do. A platform without the key logs
 * a warning and leaves everything alone (the §2 fail-soft rule for internal hooks).
 *
 * Runs from [TabsStartupActivity] for every opened project and from the settings page on apply.
 */
class NativeTabsRegistryGuard(
    private val isMac: Boolean = SystemInfo.isMac,
    private val registryKey: String = REGISTRY_KEY,
    private val settings: () -> AgenstormSettings.State = { AgenstormSettings.getInstance().state },
    private val notify: (Project?, String) -> Unit = ::showRestartNotification,
) {

    enum class Change { NONE, NATIVE_TABS_DISABLED, NATIVE_TABS_RESTORED }

    /** Aligns the registry with the toggle and reports what changed; idempotent. */
    fun sync(project: Project?): Change {
        if (!isMac) return Change.NONE
        val value = Registry.get(registryKey)
        val nativeTabsOn = try {
            value.asBoolean()
        } catch (e: MissingResourceException) {
            LOG.warn("Registry key $registryKey is not defined in this IDE; native project tabs are left alone", e)
            return Change.NONE
        }
        val state = settings()
        return when {
            state.projectTabsEnabled -> {
                if (!nativeTabsOn) return Change.NONE
                value.setValue(false)
                state.nativeTabsDisabledByAgenstorm = true
                notify(project, AgenstormBundle.message("tabs.notification.nativeTabsOff"))
                Change.NATIVE_TABS_DISABLED
            }
            state.nativeTabsDisabledByAgenstorm -> {
                state.nativeTabsDisabledByAgenstorm = false
                if (nativeTabsOn) return Change.NONE
                value.setValue(true)
                notify(project, AgenstormBundle.message("tabs.notification.nativeTabsRestored"))
                Change.NATIVE_TABS_RESTORED
            }
            else -> Change.NONE
        }
    }

    companion object {
        const val REGISTRY_KEY = "ide.mac.os.wintabs.version2"
        private val LOG = logger<NativeTabsRegistryGuard>()

        /** Settings page hook: re-align after the toggle has been applied. */
        fun syncFromSettings() {
            NativeTabsRegistryGuard().sync(project = null)
        }

        /** "Restart to apply" balloon with a Restart Now action when the IDE can restart itself. */
        fun showRestartNotification(project: Project?, message: String) {
            val notification = AgenstormNotifications.group()
                .createNotification(AgenstormBundle.message("tabs.notification.title"), message, NotificationType.INFORMATION)
                .setImportant(true)
            val application = ApplicationManager.getApplication()
            if (application.isRestartCapable) {
                notification.addAction(NotificationAction.createSimple(AgenstormBundle.message("tabs.notification.restartNow")) { application.restart() })
            }
            notification.notify(project)
        }
    }
}
