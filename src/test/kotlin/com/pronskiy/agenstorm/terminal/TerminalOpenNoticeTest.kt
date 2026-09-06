package com.pronskiy.agenstorm.terminal

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormNotifications
import com.pronskiy.agenstorm.core.AgenstormSettings

/** Step G2.4: shadowing `open` announces itself exactly once, and never again after the flag is set. */
class TerminalOpenNoticeTest : BasePlatformTestCase() {

    private val shown = mutableListOf<Notification>()

    override fun setUp() {
        super.setUp()
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        // A balloon carrying a project is published on that project's bus, which the application bus does
        // not see, so both are listened to.
        val listener = object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == AgenstormNotifications.GROUP_ID) shown.add(notification)
            }
        }
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, listener)
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, listener)
    }

    override fun tearDown() {
        try {
            project.service<OpenRequestServer>().stop()
            NioFiles.deleteRecursively(service<OpenShimScriptHolder>().binDir)
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testTheNoticeIsShownOnceAndCarriesALinkToTheSettings() {
        TerminalOpenNotice.showOnce(project)
        TerminalOpenNotice.showOnce(project)
        UIUtil.dispatchAllInvocationEvents()

        val notification = assertOneElement(shown)
        assertTrue(notification.content.contains("IDE terminals only"))
        assertEquals(1, notification.actions.size)
        assertTrue(AgenstormSettings.getInstance().state.terminalOpenNoticeShown)
    }

    fun testAFlagFromAnEarlierRunKeepsItQuiet() {
        AgenstormSettings.getInstance().state.terminalOpenNoticeShown = true

        TerminalOpenNotice.showOnce(project)
        UIUtil.dispatchAllInvocationEvents()

        assertEmpty(shown)
    }

    fun testInstallingTheShimAnnouncesItself() {
        if (SystemInfo.isWindows) return

        TerminalOpenExecOptionsCustomizer.shimFor(project, LocalEelDescriptor)
        TerminalOpenExecOptionsCustomizer.shimFor(project, LocalEelDescriptor)
        UIUtil.dispatchAllInvocationEvents()

        assertOneElement(shown)
    }
}
