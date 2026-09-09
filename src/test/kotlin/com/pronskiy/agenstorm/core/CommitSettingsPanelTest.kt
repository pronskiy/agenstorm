package com.pronskiy.agenstorm.core

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.commit.PromptBuilder
import com.pronskiy.agenstorm.commit.llm.ApiKeyStore
import java.awt.Component
import java.io.File
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** Step D3.1: the Commit messages group edits every commit setting, keeps the API keys in PasswordSafe only, tests the typed values. */
class CommitSettingsPanelTest : BasePlatformTestCase() {

    private lateinit var configurable: AgenstormConfigurable
    private lateinit var panel: JComponent

    override fun setUp() {
        super.setUp()
        AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
        ApiKeyStore.set("anthropic", null)
        ApiKeyStore.set("openai", null)
        configurable = AgenstormConfigurable()
        panel = configurable.createComponent()
    }

    override fun tearDown() {
        try {
            configurable.disposeUIResources()
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
            ApiKeyStore.set("anthropic", null)
            ApiKeyStore.set("openai", null)
        } finally {
            super.tearDown()
        }
    }

    fun testBackendSelectorListsTheBackendsAndReflectsTheState() {
        val combo = named<ComboBox<*>>("commit.backend")
        val ids = (0 until combo.itemCount).map { (combo.getItemAt(it) as AgenstormConfigurable.BackendOption).id }
        assertEquals(listOf("anthropic", "openai", "claude-cli", "fake"), ids)
        assertEquals("anthropic", (combo.selectedItem as AgenstormConfigurable.BackendOption).id)
        assertFalse(configurable.isModified)
    }

    fun testFieldsShowTheDefaultsAndApplyWritesTheState() {
        assertEquals(PromptBuilder.DEFAULT_SYSTEM, named<JBTextArea>("commit.systemPrompt").text)
        assertEquals(PromptBuilder.DEFAULT_USER, named<JBTextArea>("commit.userPrompt").text)
        assertEquals("60000", named<JBTextField>("commit.maxDiffChars").text)
        assertEquals("https://api.openai.com/v1", named<JBTextField>("commit.openai.baseUrl").text)

        val combo = named<ComboBox<*>>("commit.backend")
        combo.selectedIndex = 1
        named<JBTextField>("commit.model").text = "llama3"
        named<JBTextField>("commit.openai.baseUrl").text = "http://localhost:11434/v1"
        named<JBTextField>("commit.maxDiffChars").text = "20000"
        named<JBTextField>("commit.language").text = "German"
        named<JBCheckBox>("commit.conventional").isSelected = false
        named<JBCheckBox>("commit.body").isSelected = false
        named<JBTextArea>("commit.systemPrompt").text = "Custom system {conventional}"
        named<JBTextField>("commit.cli.extraArgs").text = "--tools \"\""
        assertTrue(configurable.isModified)

        configurable.apply()

        val state = AgenstormSettings.getInstance().state
        assertEquals("openai", state.commitBackendId)
        assertEquals("llama3", state.commitModel)
        assertEquals("http://localhost:11434/v1", state.commitOpenAiBaseUrl)
        assertEquals(20_000, state.commitMaxDiffChars)
        assertEquals("German", state.commitLanguage)
        assertFalse(state.commitConventionalCommits)
        assertFalse(state.commitBodyEnabled)
        assertEquals("Custom system {conventional}", state.commitSystemPrompt)
        assertEquals("", state.commitUserPrompt)
        assertEquals("--tools \"\"", state.commitClaudeCliExtraArgs)
        assertFalse(configurable.isModified)
    }

    fun testResetToDefaultRestoresTheBuiltInPromptAndStoresItAsEmpty() {
        named<JBTextArea>("commit.systemPrompt").text = "Custom"
        configurable.apply()
        assertEquals("Custom", AgenstormSettings.getInstance().state.commitSystemPrompt)

        named<javax.swing.JButton>("commit.systemPrompt.reset").doClick()
        assertEquals(PromptBuilder.DEFAULT_SYSTEM, named<JBTextArea>("commit.systemPrompt").text)
        configurable.apply()
        assertEquals("", AgenstormSettings.getInstance().state.commitSystemPrompt)
    }

    fun testApiKeysGoToPasswordSafeAndNeverIntoTheState() {
        named<JBPasswordField>("commit.anthropic.key").text = "sk-ant-secret"
        named<JBPasswordField>("commit.openai.key").text = "sk-oa-secret"
        assertTrue(configurable.isModified)

        configurable.apply()

        // Keys are written off the EDT, so the store catches up a moment later.
        waitFor("keys not stored") { ApiKeyStore.get("anthropic") == "sk-ant-secret" && ApiKeyStore.get("openai") == "sk-oa-secret" }
        val serialized = com.intellij.util.xmlb.XmlSerializer.serialize(AgenstormSettings.getInstance().state).toString()
        assertFalse(serialized.contains("secret"))

        // A fresh panel reads the stored keys back, in the background, without looking modified.
        val again = AgenstormConfigurable()
        try {
            val fresh = again.createComponent()
            val field = UIUtil.uiTraverser(fresh).filter { it.name == "commit.anthropic.key" }.first() as JBPasswordField
            waitFor("stored key not loaded into the fresh panel") { String(field.password) == "sk-ant-secret" }
            assertFalse(again.isModified)
        } finally {
            again.disposeUIResources()
        }
    }

    fun testAKeyTypedBeforeTheStoreAnswersIsKept() {
        ApiKeyStore.set("anthropic", "sk-ant-stored")
        ApiKeyStore.set("openai", "sk-oa-stored")
        val again = AgenstormConfigurable()
        try {
            val fresh = again.createComponent()
            val anthropic = UIUtil.uiTraverser(fresh).filter { it.name == "commit.anthropic.key" }.first() as JBPasswordField
            val openAi = UIUtil.uiTraverser(fresh).filter { it.name == "commit.openai.key" }.first() as JBPasswordField
            anthropic.text = "sk-ant-typed"
            // The OpenAI field filling up proves the background load has finished.
            waitFor("background load did not finish") { String(openAi.password) == "sk-oa-stored" }
            assertEquals("sk-ant-typed", String(anthropic.password))
            assertTrue(again.isModified)
        } finally {
            again.disposeUIResources()
        }
    }

    fun testFeatureTogglesAreStillFiveAmongTheOtherCheckBoxes() {
        val featureTexts = listOf("settings.links.enabled", "settings.frame.hideFileName", "settings.commit.enabled", "settings.tabs.enabled", "settings.markdown.liveMarkup.enabled").map(AgenstormBundle::message)
        val checkBoxes = UIUtil.findComponentsOfType(panel, JBCheckBox::class.java)
        assertEquals(5, checkBoxes.count { it.text in featureTexts })
    }

    fun testTestConnectionRunsTheCliWithTheModelAsTyped() {
        val args = Files.createTempFile("fake-claude-args", ".txt").toFile()
        val wrapper = Files.createTempFile("fake-claude", ".sh").toFile()
        try {
            val fake = File("src/test/testData/commit/fake-claude.sh").absolutePath
            wrapper.writeText("#!/bin/sh\nFAKE_CLAUDE_ARGS_FILE='${args.path}' exec '$fake' \"\$@\"\n")
            wrapper.setExecutable(true)
            named<ComboBox<*>>("commit.backend").selectedIndex = 2
            named<TextFieldWithBrowseButton>("commit.cli.path").text = wrapper.path
            named<JBTextField>("commit.model").text = "opus"

            named<JButton>("commit.test").doClick()

            val result = named<JLabel>("commit.testResult")
            val running = AgenstormBundle.message("settings.commit.test.running")
            PlatformTestUtil.waitWithEventsDispatching("Test Connection did not finish", { result.text.isNotEmpty() && result.text != running }, 20)
            assertEquals(AgenstormBundle.message("settings.commit.test.ok"), result.text)
            val recorded = args.readText().removeSuffix("\n").split("\n")
            val modelIndex = recorded.indexOf("--model")
            assertTrue(recorded.toString(), modelIndex >= 0)
            assertEquals(recorded.toString(), "opus", recorded[modelIndex + 1])
        } finally {
            wrapper.delete()
            args.delete()
        }
    }

    private fun waitFor(message: String, condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching(message, condition, 10)

    private inline fun <reified T : Component> named(name: String): T {
        val component = UIUtil.uiTraverser(panel).filter { it.name == name }.first()
        assertNotNull("no component named $name", component)
        return component as T
    }

    @Suppress("unused")
    private fun dump(): String = UIUtil.uiTraverser(panel).filter { it.name != null && it !is JPanel }.joinToString { "${it.name}:${it.javaClass.simpleName}" }
}
