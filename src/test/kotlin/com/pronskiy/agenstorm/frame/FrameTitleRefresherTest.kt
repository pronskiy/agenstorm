package com.pronskiy.agenstorm.frame

import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.UISettingsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Step C1.4: toggling "hide the file name" refreshes the frames that are already open. The refresher does
 * that through the UI settings topic, which is what makes the platform recompute both parts of the title.
 */
class FrameTitleRefresherTest : BasePlatformTestCase() {

    fun testRefreshFiresTheUiSettingsTopic() {
        var fired = 0
        val connection = ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
        connection.subscribe(UISettingsListener.TOPIC, UISettingsListener { fired++ })

        FrameTitleRefresher.refreshOpenFrames()

        assertEquals(1, fired)
    }

    fun testRefreshDoesNotThrowWithoutOpenFrames() {
        // The settings page calls this on Apply whatever is on screen; a headless run has no project frame.
        FrameTitleRefresher.refreshOpenFrames()
        assertNotNull(UISettings.getInstance())
    }
}
