package com.pronskiy.agenstorm.notifications

import com.intellij.notification.NotificationType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step L1.1: which balloons get a shortened fadeout, and how long it is. `null` means "leave the platform's
 * own timer alone" — 10 s for a `BALLOON` group, 5 min for a `STICKY_BALLOON` one.
 */
class AutoDismissPolicyTest : BasePlatformTestCase() {

    private fun state(
        enabled: Boolean = true,
        seconds: Int = 5,
        errors: Boolean = false,
    ): AgenstormSettings.State = AgenstormSettings.State(
        notificationsAutoDismissEnabled = enabled,
        notificationsAutoDismissSeconds = seconds,
        notificationsAutoDismissErrors = errors,
    )

    fun testFeatureOffLeavesEveryBalloonToThePlatform() {
        val off = state(enabled = false)
        for (type in NotificationType.entries) {
            assertNull("$type should be left alone", AutoDismissPolicy.delayMs(type, off))
        }
    }

    fun testInformationGetsTheConfiguredDelay() {
        assertEquals(5_000, AutoDismissPolicy.delayMs(NotificationType.INFORMATION, state()))
    }

    fun testWarningAndIdeUpdateAreDismissedToo() {
        assertEquals(5_000, AutoDismissPolicy.delayMs(NotificationType.WARNING, state()))
        assertEquals(5_000, AutoDismissPolicy.delayMs(NotificationType.IDE_UPDATE, state()))
    }

    fun testErrorsKeepThePlatformTimerByDefault() {
        assertNull(AutoDismissPolicy.delayMs(NotificationType.ERROR, state()))
    }

    fun testErrorsAreDismissedOnceOptedIn() {
        assertEquals(5_000, AutoDismissPolicy.delayMs(NotificationType.ERROR, state(errors = true)))
    }

    fun testAConfiguredDelayIsHonoured() {
        assertEquals(12_000, AutoDismissPolicy.delayMs(NotificationType.INFORMATION, state(seconds = 12)))
    }

    fun testDelayBelowTheRangeIsClampedUp() {
        assertEquals(1_000, AutoDismissPolicy.delayMs(NotificationType.INFORMATION, state(seconds = 0)))
        assertEquals(1_000, AutoDismissPolicy.delayMs(NotificationType.INFORMATION, state(seconds = -30)))
    }

    fun testDelayAboveTheRangeIsClampedDown() {
        assertEquals(600_000, AutoDismissPolicy.delayMs(NotificationType.INFORMATION, state(seconds = 99_999)))
    }
}
