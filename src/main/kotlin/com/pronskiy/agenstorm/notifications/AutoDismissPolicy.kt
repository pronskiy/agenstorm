package com.pronskiy.agenstorm.notifications

import com.intellij.notification.NotificationType
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Epic L: how long a notification balloon should stay on screen, in milliseconds, or `null` for "leave it to
 * the platform" — which is 10 s for a group whose display type is `BALLOON` and 5 min for a `STICKY_BALLOON`
 * one (`NotificationsManagerImpl.notifyByBalloon`).
 *
 * The whole feature's decision lives here so it can be tested without a balloon on screen.
 */
object AutoDismissPolicy {

    /**
     * What the settings page accepts. Below a second nothing is readable; past ten minutes the platform's own
     * sticky timer is the shorter of the two, so the feature would stop having an effect.
     */
    val SECONDS_RANGE = 1..600

    fun delayMs(type: NotificationType, state: AgenstormSettings.State): Int? {
        if (!state.notificationsAutoDismissEnabled) return null
        if (type == NotificationType.ERROR && !state.notificationsAutoDismissErrors) return null
        return state.notificationsAutoDismissSeconds.coerceIn(SECONDS_RANGE) * 1000
    }
}
