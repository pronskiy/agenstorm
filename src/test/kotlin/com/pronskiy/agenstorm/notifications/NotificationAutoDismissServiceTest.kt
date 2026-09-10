package com.pronskiy.agenstorm.notifications

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step L1.3: the scheduled coroutine has to actually run. Headless there is never a balloon, so the run ends
 * in the "gave up waiting" branch — what this asserts is that the body executes and finishes cleanly rather
 * than dying on its dispatcher or its context.
 */
class NotificationAutoDismissServiceTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
    }

    fun testTheScheduledCoroutineRunsToCompletion() {
        val notification = Notification("Agenstorm", "title", "content", NotificationType.INFORMATION)

        val job = NotificationAutoDismissService.getInstance().schedule(notification)

        assertNotNull("an information notification should have been scheduled", job)
        val deadline = System.currentTimeMillis() + 15_000
        while (!job!!.isCompleted && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertTrue("the coroutine never completed — it is stuck on its dispatcher", job.isCompleted)
        assertFalse("the coroutine failed instead of completing", job.isCancelled)
    }

    fun testAnErrorNotificationIsNotScheduledAtAll() {
        val notification = Notification("Agenstorm", "title", "content", NotificationType.ERROR)

        assertNull(NotificationAutoDismissService.getInstance().schedule(notification))
    }
}
