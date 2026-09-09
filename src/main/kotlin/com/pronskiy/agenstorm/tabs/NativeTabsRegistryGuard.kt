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
 * Step E1.1. Keeps the macOS window tabs (registry `ide.mac.os.wintabs.version2`, restart required) **on**.
 *
 * That key does not merely style the tabs: under the New UI it is the switch `JdkEx.getTabbingModeInvocator()`
 * reads, so turning it off makes `isTabbingModeAvailable()` false, `MacWinTabsHandlerV2.initFrame` never calls
 * `JdkEx.setTabbingMode`, and no `NSWindowTabGroup` is formed — every project becomes its own window. Agenstorm
 * 1.0 turned the key off to be rid of the tab row and paid exactly that price. The row is now hidden on its own
 * ([NativeTabStrip]) and the key is left alone, so the projects share one window while the tabs live in the
 * toolbar.
 *
 * All this class still does is undo 1.0: when [AgenstormSettings.State.nativeTabsDisabledByAgenstorm] says
 * Agenstorm was the one that turned the key off, it is turned back on once and the user is offered a restart.
 * A user who disabled the window tabs themselves keeps that choice — their toolbar strip then switches windows,
 * the way it does on Windows and Linux, where the platform has no window merging at all.
 *
 * Runs from [TabsStartupActivity] for every opened project and from the settings page on apply.
 */
class NativeTabsRegistryGuard(
    private val isMac: Boolean = SystemInfo.isMac,
    private val registryKey: String = REGISTRY_KEY,
    private val settings: () -> AgenstormSettings.State = { AgenstormSettings.getInstance().state },
    private val notify: (Project?, String) -> Unit = ::showRestartNotification,
) {

    enum class Change { NONE, NATIVE_TABS_RESTORED }

    /** Gives back the window tabs if Agenstorm ever took them away; idempotent. */
    fun sync(project: Project?): Change {
        if (!isMac) return Change.NONE
        val state = settings()
        if (!state.nativeTabsDisabledByAgenstorm) return Change.NONE
        val value = Registry.get(registryKey)
        val nativeTabsOn = try {
            value.asBoolean()
        } catch (e: MissingResourceException) {
            // Fail soft (§2): without the key we cannot restore anything, and the flag stays for an IDE that has it.
            LOG.warn("Registry key $registryKey is not defined in this IDE; macOS window tabs are left alone", e)
            return Change.NONE
        }
        state.nativeTabsDisabledByAgenstorm = false
        if (nativeTabsOn) return Change.NONE
        value.setValue(true)
        notify(project, AgenstormBundle.message("tabs.notification.nativeTabsRestored"))
        return Change.NATIVE_TABS_RESTORED
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
