package com.pronskiy.agenstorm.worktrees

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormConfigurable
import com.pronskiy.agenstorm.core.AgenstormSettings
import com.pronskiy.agenstorm.core.AgenstormSettingsListener
import com.pronskiy.agenstorm.worktrees.create.WorktreeLimit
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

    fun testASwitchReplacesTheCurrentProjectUnlessSetToKeepIt() {
        val state = AgenstormSettings.getInstance().state
        val radios = UIUtil.uiTraverser(panel).filter(com.intellij.ui.components.JBRadioButton::class.java).toList().associateBy { it.name }
        assertFalse(state.worktreesKeepCurrentOpen)
        assertTrue(radios.getValue("worktrees.switch.replace").isSelected)

        radios.getValue("worktrees.switch.keep").isSelected = true
        configurable.apply()

        assertTrue(state.worktreesKeepCurrentOpen)
    }

    fun testWorktreesMadeElsewhereArePreparedOnFirstOpenButNotOnArrivalByDefault() {
        val state = AgenstormSettings.getInstance().state
        val boxes = UIUtil.uiTraverser(panel).filter(com.intellij.ui.components.JBCheckBox::class.java).toList().associateBy { it.name }
        assertTrue(state.worktreesPrepareOnOpen)
        assertFalse(state.worktreesPrepareOnAppear)
        assertTrue(boxes.getValue("worktrees.prepareOnOpen").isSelected)
        assertFalse(boxes.getValue("worktrees.prepareOnAppear").isSelected)

        boxes.getValue("worktrees.prepareOnOpen").isSelected = false
        boxes.getValue("worktrees.prepareOnAppear").isSelected = true
        configurable.apply()

        assertFalse(state.worktreesPrepareOnOpen)
        assertTrue(state.worktreesPrepareOnAppear)
    }

    fun testTheWorktreeLimitIsOffByDefaultAndFifteenWhenOn() {
        val state = AgenstormSettings.State()
        assertFalse(state.worktreesLimitEnabled)
        assertEquals(15, state.worktreesLimit)
        assertFalse(WorktreeLimit.reached(linked = 40, enabled = false, limit = 15))
        assertFalse(WorktreeLimit.reached(linked = 14, enabled = true, limit = 15))
        assertTrue(WorktreeLimit.reached(linked = 15, enabled = true, limit = 15))
        val field = UIUtil.uiTraverser(panel).filter(javax.swing.JTextField::class.java).first { it.name == "worktrees.limit" }
        assertFalse("disabled while the limit is off", field.isEnabled)
    }

    fun testTheAutomaticCleanupIsOffByDefaultAndThirtyDaysWhenOn() {
        val state = AgenstormSettings.State()
        assertFalse(state.worktreesAutoCleanup)
        assertEquals(30, state.worktreesAutoCleanupDays)
        val field = UIUtil.uiTraverser(panel).filter(javax.swing.JTextField::class.java).first { it.name == "worktrees.autoCleanupDays" }
        assertFalse("disabled while the cleanup is off", field.isEnabled)
    }

    fun testTmuxBackedTerminalsAreOffByDefault() {
        val state = AgenstormSettings.getInstance().state
        val box = UIUtil.uiTraverser(panel).filter(com.intellij.ui.components.JBCheckBox::class.java).first { it.name == "worktrees.tmux" }
        assertFalse(state.terminalTmuxEnabled)
        assertFalse(box.isSelected)

        box.isSelected = true
        configurable.apply()

        assertTrue(state.terminalTmuxEnabled)
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
