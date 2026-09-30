package com.pronskiy.agenstorm.worktrees

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormConfigurable
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import javax.swing.JComponent

/** Step T1.8: the Worktrees group, and the folder setting as the feature reads it. */
class WorktreesSettingsTest : BasePlatformTestCase() {

    private lateinit var configurable: AgenstormConfigurable
    private lateinit var panel: JComponent

    override fun setUp() {
        super.setUp()
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        configurable = AgenstormConfigurable()
        panel = configurable.createComponent()!!
    }

    override fun tearDown() {
        try {
            configurable.disposeUIResources()
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testTheFeatureIsOnByDefaultAndTheFolderIsEditable() {
        val state = AgenstormSettings.getInstance().state
        assertTrue(state.worktreesEnabled)
        val folder = UIUtil.uiTraverser(panel).filter(JBTextField::class.java).first { it.name == "worktrees.folder" }
        assertEquals(".worktrees", folder.text)

        folder.text = "  wt  "
        configurable.apply()

        assertEquals("wt", state.worktreesFolder)
        assertEquals("wt", WorktreeExcludes.configuredFolder())
    }

    fun testTurningTheFeatureOffIsAnnounced() {
        var announced = 0
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(AgenstormSettingsListener.TOPIC, AgenstormSettingsListener { announced++ })
        val toggle = UIUtil.uiTraverser(panel).filter(com.intellij.ui.components.JBCheckBox::class.java)
            .first { it.text.startsWith("Show the worktrees") }

        toggle.isSelected = false
        configurable.apply()

        assertFalse(AgenstormSettings.getInstance().state.worktreesEnabled)
        assertTrue(announced > 0)
    }
}
