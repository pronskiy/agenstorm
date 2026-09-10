package com.pronskiy.agenstorm.notifications

import com.intellij.notification.Notification
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.ui.BalloonImpl
import com.pronskiy.agenstorm.core.AgenstormSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Epic L: gives a notification's balloon a shorter fadeout than the platform's.
 *
 * `Balloon.startFadeoutTimer(ms)` cancels whatever hide request is pending and schedules its own, so calling
 * it after the platform has armed its 10 s / 5 min timer simply replaces it. The balloon has already been
 * marked "smart" by `NotificationsManagerImpl`, and its `ApplicationActivationListener` recomputes the
 * remaining time from the delay we set — so the countdown keeps pausing while the IDE is in the background,
 * and a notification that arrived while you were in another app is still there when you come back.
 *
 * `BalloonImpl` carries no class-level `@ApiStatus`; the two members annotated `@ApiStatus.Internal` inside
 * it are `getShadowBorderProvider` and `setShadowBorderProvider`, neither of which is touched here. The
 * `hide()` fallback keeps the feature working on the day the platform hands out some other [Balloon].
 */
@Service(Service.Level.APP)
class NotificationAutoDismissService(private val scope: CoroutineScope) {

    /**
     * Called for every notification as it is published. The balloon does not exist yet at that point — it is
     * created further down the same publish — so this waits for one to appear and gives up quietly if none
     * ever does, which is what a group set to "No popups" or to the tool window looks like from here.
     */
    fun schedule(notification: Notification): Job? {
        val delayMs = AutoDismissPolicy.delayMs(notification.type, AgenstormSettings.getInstance().state)
        if (LOG.isDebugEnabled) LOG.debug("notified: ${notification.groupId} ${notification.type} -> ${delayMs ?: "left to the platform"}")
        if (delayMs == null) return null
        // ModalityState.any(): balloons show over modal dialogs too, and all this touches is a UI timer.
        return scope.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            val balloon = awaitBalloon(notification) ?: return@launch
            rearm(balloon, delayMs)
            if (LOG.isDebugEnabled) LOG.debug("re-armed ${notification.groupId} (${balloon.javaClass.name}) to ${delayMs}ms")
        }
    }

    private suspend fun awaitBalloon(notification: Notification): Balloon? {
        var waited = 0
        while (waited <= BALLOON_WAIT_MS) {
            if (notification.isExpired) {
                if (LOG.isDebugEnabled) LOG.debug("${notification.groupId} expired after ${waited}ms, before a balloon appeared")
                return null
            }
            notification.balloon?.takeIf { !it.isDisposed }?.let { return it }
            delay(POLL_MS.toLong())
            waited += POLL_MS
        }
        if (LOG.isDebugEnabled) LOG.debug("${notification.groupId} (${notification.type}) produced no balloon within ${BALLOON_WAIT_MS}ms")
        return null
    }

    private suspend fun rearm(balloon: Balloon, delayMs: Int) {
        val impl = balloon as? BalloonImpl
        if (impl != null) {
            impl.startFadeoutTimer(delayMs)
            return
        }
        // Public API only. Unlike the platform's timer this one keeps running while the IDE is in the
        // background, so it is the fallback rather than the default.
        delay(delayMs.toLong())
        if (!balloon.isDisposed) balloon.hide()
    }

    companion object {
        private val LOG = logger<NotificationAutoDismissService>()
        private const val POLL_MS = 50
        private const val BALLOON_WAIT_MS = 3_000

        fun getInstance(): NotificationAutoDismissService =
            ApplicationManager.getApplication().getService(NotificationAutoDismissService::class.java)
    }
}
