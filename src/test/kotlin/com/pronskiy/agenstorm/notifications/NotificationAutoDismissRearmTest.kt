package com.pronskiy.agenstorm.notifications

import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.BalloonImpl
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

    fun testTheSameHoldsForAPlainBalloonGroup() {
        val balloon = newBalloon()
        balloon.startSmartFadeoutTimer(10_000) // what the platform does for a BALLOON group

        NotificationAutoDismissService.getInstance().applyDelay(balloon, 5_000)

        assertEquals(5_000, smartDelayOf(balloon))
    }
}
