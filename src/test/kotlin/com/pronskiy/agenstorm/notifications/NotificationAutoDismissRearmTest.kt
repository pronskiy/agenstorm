package com.pronskiy.agenstorm.notifications

import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.ui.BalloonImpl
import com.pronskiy.agenstorm.core.AgenstormSettings
import javax.swing.JLabel

/**
 * Step L1.7, the bug Roman hit: the commit popup stayed. `NotificationsManagerImpl` gives a `STICKY_BALLOON`
 * group's balloon `startSmartFadeoutTimer(300_000)` and starts no alarm at all — `BalloonImpl`'s own
 * `AWTEventListener` starts it on the first event with `if (mySmartFadeoutDelay > 0) startFadeoutTimer(mySmartFadeoutDelay)`.
 * So arming only the plain timer is pointless: the first mouse move cancels our request and puts five
 * minutes back. The delay that has to carry ours is the *smart* one.
 *
 * The assertion reads that private field because it is the thing that actually governs the behaviour; if the
 * platform ever renames it this test is exactly the alarm we want.
 */
class NotificationAutoDismissRearmTest : BasePlatformTestCase() {

    private fun newBalloon(): BalloonImpl {
        val balloon = JBPopupFactory.getInstance().createBalloonBuilder(JLabel("content")).createBalloon()
        Disposer.register(testRootDisposable, balloon)
        return balloon as BalloonImpl
    }

    private fun smartDelayOf(balloon: Balloon): Int =
        BalloonImpl::class.java.getDeclaredField("mySmartFadeoutDelay")
            .apply { isAccessible = true }
            .getInt(balloon)

    fun testOurDelayReplacesTheStickyOneTheAwtListenerWouldReArmFrom() {
        val balloon = newBalloon()
        balloon.startSmartFadeoutTimer(300_000) // what the platform does for "Vcs Important Notifications"

        val handled = NotificationAutoDismissService.getInstance().applyDelay(balloon, 5_000)

        assertTrue("a BalloonImpl should be handled without the fallback", handled)
        assertEquals("the AWT listener would re-arm five minutes again", 5_000, smartDelayOf(balloon))
    }

    private fun plainDelayOf(balloon: Balloon): Int =
        BalloonImpl::class.java.getDeclaredField("myFadeoutRequestDelay")
            .apply { isAccessible = true }
            .getInt(balloon)

    /** Guardrail "Background pause": with the IDE not the active application, nothing starts counting yet. */
    fun testNothingStartsCountingWhileTheIdeIsInTheBackground() {
        val balloon = newBalloon()

        NotificationAutoDismissService.getInstance().applyDelay(balloon, 5_000, startNow = false)

        assertEquals("our delay still has to be the one the AWT listener will re-arm from", 5_000, smartDelayOf(balloon))
        assertEquals("no countdown may be running while the user is in another app", 0, plainDelayOf(balloon))
    }

    /** ...and with the user actually at the IDE, the countdown starts at once rather than on the next event. */
    fun testTheCountdownStartsAtOnceWhenTheIdeIsActive() {
        val balloon = newBalloon()

        NotificationAutoDismissService.getInstance().applyDelay(balloon, 5_000, startNow = true)

        assertEquals(5_000, smartDelayOf(balloon))
        assertEquals(5_000, plainDelayOf(balloon))
    }

    /** Guardrail "Nothing is lost": the popup goes, the notification does not — we never expire it. */
    fun testTheNotificationIsNeverExpired() {
        val balloon = newBalloon()
        val notification = Notification("Agenstorm", "title", "content", NotificationType.INFORMATION)
        notification.setBalloon(balloon)

        NotificationAutoDismissService.getInstance().applyDelay(balloon, 5_000, startNow = true)
        balloon.hide()

        assertFalse("hiding the balloon must not drop the entry from the Notifications tool window", notification.isExpired)
    }

    /** Guardrail "Off switch": the setting is read per notification, so switching it off needs no restart. */
    fun testTurningTheFeatureOffTakesEffectWithoutARestart() {
        val service = NotificationAutoDismissService.getInstance()
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State())

        val scheduled = service.schedule(Notification("Agenstorm", "t", "c", NotificationType.INFORMATION))
        assertNotNull("the feature is on by default", scheduled)
        scheduled!!.cancel()

        AgenstormSettings.getInstance().state.notificationsAutoDismissEnabled = false
        try {
            assertNull(
                "the next notification must already be left to the platform",
                service.schedule(Notification("Agenstorm", "t", "c", NotificationType.INFORMATION)),
            )
        } finally {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        }
    }

    fun testTheSameHoldsForAPlainBalloonGroup() {
        val balloon = newBalloon()
        balloon.startSmartFadeoutTimer(10_000) // what the platform does for a BALLOON group

        NotificationAutoDismissService.getInstance().applyDelay(balloon, 5_000)

        assertEquals(5_000, smartDelayOf(balloon))
    }
}
