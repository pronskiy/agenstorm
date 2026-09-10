package com.pronskiy.agenstorm.terminal

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormNotifications
import com.pronskiy.agenstorm.core.AgenstormSettings

/**
 * Step J1.6: ⌘⌥M belongs to Extract Method in the macOS keymaps, and this is what decides that Maximize
 * Terminal wins it — without touching anyone's keymap, which is the whole point of decision 46.
 */
class TerminalMaximizeShortcutPromoterTest : BasePlatformTestCase() {

    private val promoter = TerminalMaximizeShortcutPromoter()
    private val ours = TerminalMaximizeToggleAction()
    private val extractMethod = named("Extract Method...")
    private val extractFunction = named("Extract Function...")
    private val shown = mutableListOf<Notification>()

    override fun setUp() {
        super.setUp()
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
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
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testOurActionGoesFirstAndNothingIsDropped() {
        val promoted = promoter.promote(listOf(extractMethod, ours, extractFunction), context())

        assertEquals(listOf(ours, extractMethod, extractFunction), promoted)
    }

    fun testTheOrderOfEverythingElseIsLeftAlone() {
        val promoted = promoter.promote(listOf(extractFunction, extractMethod, ours), context())

        assertEquals(listOf(ours, extractFunction, extractMethod), promoted)
    }

    fun testTheFeatureOffLeavesTheKeystrokeToWhoeverElseWantsIt() {
        AgenstormSettings.getInstance().state.terminalMaximizeEnabled = false

        assertNull(promoter.promote(listOf(extractMethod, ours), context()))
    }

    fun testAKeystrokeOfOurOwnIsNotWorthReordering() {
        assertNull("nothing of ours in the list", promoter.promote(listOf(extractMethod), context()))
        assertNull("nobody to win against", promoter.promote(listOf(ours), context()))
    }

    fun testTheChangeOfHandsIsAnnouncedOnceAndNamesWhatItTook() {
        promoter.promote(listOf(extractMethod, ours), context())
        promoter.promote(listOf(extractMethod, ours), context())
        UIUtil.dispatchAllInvocationEvents()

        val notification = assertOneElement(shown)
        assertTrue(notification.content, notification.content.contains("Extract Method..."))
        assertEquals(1, notification.actions.size)
        assertTrue(AgenstormSettings.getInstance().state.terminalMaximizeShortcutNoticeShown)
    }

    fun testAnUncontestedPressSaysNothing() {
        promoter.promote(listOf(ours), context())
        UIUtil.dispatchAllInvocationEvents()

        assertEmpty(shown)
        assertFalse(AgenstormSettings.getInstance().state.terminalMaximizeShortcutNoticeShown)
    }

    private fun context(): DataContext = SimpleDataContext.getProjectContext(project)

    private fun named(text: String): AnAction = object : AnAction(text) {
        override fun actionPerformed(e: AnActionEvent) = Unit
    }
}
