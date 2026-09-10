package com.pronskiy.agenstorm.notifications

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Step L1.4: `Notifications.TOPIC` is `BroadcastDirection.NONE`, and `Notifications.Bus` publishes a
 * project-scoped notification on that project's bus and everything else on the application's. The commit
 * result is a project-scoped one, so a registration on the application bus alone would never see it — hence
 * the two `<listener>` entries this pins.
 */
class NotificationAutoDismissListenerTest : BasePlatformTestCase() {

    private fun descriptor(): String =
        javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")!!.reader().use { it.readText() }

    private fun listenersIn(section: String): String =
        descriptor().substringAfter("<$section>").substringBefore("</$section>")

    fun testTheListenerIsRegisteredOnTheApplicationBus() {
        assertTrue(
            "the application-bus registration is gone",
            listenersIn("applicationListeners").contains(NotificationAutoDismissListener::class.java.name),
        )
    }

    fun testTheListenerIsRegisteredOnTheProjectBusToo() {
        assertTrue(
            "without the project-bus registration, project-scoped notifications (the commit result among them) are never seen",
            listenersIn("projectListeners").contains(NotificationAutoDismissListener::class.java.name),
        )
    }

    fun testTheListenerImplementsTheTopicInterface() {
        assertTrue(Notifications::class.java.isAssignableFrom(NotificationAutoDismissListener::class.java))
    }

    /** A publish on the project bus must reach a `Notifications` listener there: the premise of the whole wiring. */
    fun testAProjectBusPublishReachesAProjectBusListener() {
        var seen: Notification? = null
        val connection = project.messageBus.connect(testRootDisposable)
        connection.subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                seen = notification
            }
        })

        val published = Notification("Agenstorm", "title", "content", NotificationType.INFORMATION)
        published.notify(project)

        assertSame(published, seen)
    }
}
