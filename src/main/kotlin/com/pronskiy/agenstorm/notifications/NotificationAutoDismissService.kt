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
 * The balloon keeps its own fadeout machinery; all this does is replace the delay that machinery uses. The
 * notification itself is never expired, so whatever we hide is still listed in the Notifications tool window.
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

    /**
     * Re-arms the balloon's own fadeout to [delayMs]. Returns false when the balloon is not a [BalloonImpl]
     * and the caller has to fall back to hiding it itself.
     *
     * The delay that governs is the **smart** one. `NotificationsManagerImpl` hands a balloon
     * `startSmartFadeoutTimer(10_000)` — or `300_000` for a `STICKY_BALLOON` group like the one the commit
     * result uses — and starts no alarm at all; `BalloonImpl`'s own `AWTEventListener` starts it on the first
     * event with `if (mySmartFadeoutDelay > 0) startFadeoutTimer(mySmartFadeoutDelay)`. So arming only the
     * plain timer is undone by the first mouse move, which is what left the commit popup on screen.
     */
    fun applyDelay(
        balloon: Balloon,
        delayMs: Int,
        startNow: Boolean = ApplicationManager.getApplication().isActive,
    ): Boolean {
        val impl = balloon as? BalloonImpl ?: return false
        impl.startSmartFadeoutTimer(delayMs)
        // [startNow] is "the user is actually looking at the IDE": start counting instead of waiting for that
        // first AWT event. With the application in the background nothing is started, which is what keeps a
        // notification that arrived while you were elsewhere on screen until you come back to it — the AWT
        // listener then starts it from the smart delay set above, so it is still ours and still five seconds.
        if (startNow) impl.startFadeoutTimer(delayMs)
        return true
    }

    private suspend fun rearm(balloon: Balloon, delayMs: Int) {
        if (applyDelay(balloon, delayMs)) return
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
