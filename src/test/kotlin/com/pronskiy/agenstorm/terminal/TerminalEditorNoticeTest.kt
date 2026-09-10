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

/**
 * Step K1.5: taking over `$EDITOR` announces itself exactly once, and says what happens to the editor the
 * user set themselves — the balloon is the only place that answers "where did my vim go".
 */
class TerminalEditorNoticeTest : BasePlatformTestCase() {

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
        TerminalEditorNotice.showOnce(project, fallback = null)
        TerminalEditorNotice.showOnce(project, fallback = null)
        UIUtil.dispatchAllInvocationEvents()

        val notification = assertOneElement(shown)
        assertTrue(notification.content.contains("IDE terminals only"))
        assertEquals(1, notification.actions.size)
        assertTrue(AgenstormSettings.getInstance().state.terminalEditorNoticeShown)
    }

    fun testTheEditorTheUserSetIsNamedAsWhatStillHandlesTheRest() {
        TerminalEditorNotice.showOnce(project, fallback = "vim")
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(assertOneElement(shown).content.contains("vim"))
    }

    fun testAFlagFromAnEarlierRunKeepsItQuiet() {
        AgenstormSettings.getInstance().state.terminalEditorNoticeShown = true

        TerminalEditorNotice.showOnce(project, fallback = null)
        UIUtil.dispatchAllInvocationEvents()

        assertEmpty(shown)
    }

    fun testInstallingTheBridgeAnnouncesItself() {
        if (SystemInfo.isWindows) return
        // The `open` shim has a balloon of its own, and this test is about this one.
        AgenstormSettings.getInstance().state.terminalOpenEnabled = false

        TerminalOpenExecOptionsCustomizer.shimFor(project, LocalEelDescriptor)
        TerminalOpenExecOptionsCustomizer.shimFor(project, LocalEelDescriptor)
        UIUtil.dispatchAllInvocationEvents()

        assertOneElement(shown)
        assertTrue(AgenstormSettings.getInstance().state.terminalEditorNoticeShown)
    }

    fun testTheBridgeOffSaysNothing() {
        if (SystemInfo.isWindows) return
        AgenstormSettings.getInstance().state.terminalEditorEnabled = false
        AgenstormSettings.getInstance().state.terminalOpenNoticeShown = true

        TerminalOpenExecOptionsCustomizer.shimFor(project, LocalEelDescriptor)
        UIUtil.dispatchAllInvocationEvents()

        assertEmpty(shown)
        assertFalse(AgenstormSettings.getInstance().state.terminalEditorNoticeShown)
    }
}
