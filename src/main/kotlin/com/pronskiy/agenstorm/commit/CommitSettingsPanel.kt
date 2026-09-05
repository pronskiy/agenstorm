package com.pronskiy.agenstorm.commit

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.layout.selectedValueMatches
import com.pronskiy.agenstorm.commit.llm.AnthropicBackend
import com.pronskiy.agenstorm.commit.llm.ApiKeyStore
import com.pronskiy.agenstorm.commit.llm.ClaudeCliBackend
import com.pronskiy.agenstorm.commit.llm.FakeBackend
import com.pronskiy.agenstorm.commit.llm.LlmBackend
import com.pronskiy.agenstorm.commit.llm.OpenAiCompatibleBackend
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormConfigurable.BackendOption
import com.pronskiy.agenstorm.core.AgenstormSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JLabel

/**
 * The rows of the "Commit messages" settings group. API keys are read from [ApiKeyStore] when the panel is
 * built and written back on apply; they never touch `agenstorm.xml`. "Test Connection" runs the selected
 * backend's `validate()` with the values currently typed (not yet applied) on [scope].
 */
class CommitSettingsPanel(private val scope: CoroutineScope) {

    private val state: AgenstormSettings.State
        get() = AgenstormSettings.getInstance().state

    private var anthropicKey: String = ""
    private var openAiKey: String = ""
    private var cliPath: String
        get() = state.commitClaudeCliPath
        set(value) {
            state.commitClaudeCliPath = value.trim()
        }

    private lateinit var backendCombo: ComboBox<BackendOption>
    private lateinit var modelField: JBTextField
    private lateinit var anthropicKeyField: JBPasswordField
    private lateinit var openAiKeyField: JBPasswordField
    private lateinit var baseUrlField: JBTextField
    private lateinit var cliPathField: TextFieldWithBrowseButton
    private lateinit var extraArgsField: JBTextField
    private var testResult: JLabel? = null

    fun render(panel: Panel) {
        anthropicKey = ApiKeyStore.get(AnthropicBackend.ID) ?: ""
        openAiKey = ApiKeyStore.get(OpenAiCompatibleBackend.ID) ?: ""

        panel.row(AgenstormBundle.message("settings.commit.backend")) {
            backendCombo = comboBox(BACKENDS)
                .bindItem(
                    { BACKENDS.firstOrNull { it.id == state.commitBackendId } ?: BACKENDS.first() },
                    { state.commitBackendId = (it ?: BACKENDS.first()).id },
                )
                .applyToComponent { name = "commit.backend" }
                .component
        }
        val anthropic = backendCombo.selectedValueMatches { it?.id == AnthropicBackend.ID }
        val openAi = backendCombo.selectedValueMatches { it?.id == OpenAiCompatibleBackend.ID }
        val cli = backendCombo.selectedValueMatches { it?.id == ClaudeCliBackend.ID }

        panel.row(AgenstormBundle.message("settings.commit.model")) {
            modelField = textField()
                .bindText({ state.commitModel }, { state.commitModel = it.trim() })
                .align(AlignX.FILL)
                .applyToComponent { name = "commit.model" }
                .comment(AgenstormBundle.message("settings.commit.model.comment", AnthropicBackend.DEFAULT_MODEL, ClaudeCliBackend.DEFAULT_MODEL))
                .component
        }
        panel.row(AgenstormBundle.message("settings.commit.apiKey")) {
            anthropicKeyField = passwordField()
                .bindText(::anthropicKey)
                .onApply { ApiKeyStore.set(AnthropicBackend.ID, anthropicKey) }
                .align(AlignX.FILL)
                .applyToComponent { name = "commit.anthropic.key" }
                .comment(AgenstormBundle.message("settings.commit.apiKey.comment"))
                .component
        }.visibleIf(anthropic)
        panel.row(AgenstormBundle.message("settings.commit.openai.baseUrl")) {
            baseUrlField = textField()
                .bindText({ state.commitOpenAiBaseUrl }, { state.commitOpenAiBaseUrl = it.trim() })
                .align(AlignX.FILL)
                .applyToComponent { name = "commit.openai.baseUrl" }
                .comment(AgenstormBundle.message("settings.commit.openai.baseUrl.comment"))
                .component
        }.visibleIf(openAi)
        panel.row(AgenstormBundle.message("settings.commit.apiKey")) {
            openAiKeyField = passwordField()
                .bindText(::openAiKey)
                .onApply { ApiKeyStore.set(OpenAiCompatibleBackend.ID, openAiKey) }
                .align(AlignX.FILL)
                .applyToComponent { name = "commit.openai.key" }
                .comment(AgenstormBundle.message("settings.commit.openai.apiKey.comment"))
                .component
        }.visibleIf(openAi)
        panel.row(AgenstormBundle.message("settings.commit.cli.path")) {
            cliPathField = textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile(), null) { it.path }
                .bindText(::cliPath)
                .align(AlignX.FILL)
                .applyToComponent { name = "commit.cli.path" }
                .comment(AgenstormBundle.message("settings.commit.cli.path.comment"))
                .component
        }.visibleIf(cli)
        panel.row(AgenstormBundle.message("settings.commit.cli.extraArgs")) {
            extraArgsField = textField()
                .bindText({ state.commitClaudeCliExtraArgs }, { state.commitClaudeCliExtraArgs = it.trim() })
                .align(AlignX.FILL)
                .applyToComponent { name = "commit.cli.extraArgs" }
                .comment(AgenstormBundle.message("settings.commit.cli.extraArgs.comment"))
                .component
        }.visibleIf(cli)
        panel.row {
            button(AgenstormBundle.message("settings.commit.test")) { testConnection() }
                .applyToComponent { name = "commit.test" }
            testResult = label("").applyToComponent { name = "commit.testResult" }.component
        }
        panel.row(AgenstormBundle.message("settings.commit.maxDiffChars")) {
            intTextField(1_000..1_000_000, 1_000)
                .bindIntText(MutableProperty({ state.commitMaxDiffChars }, { state.commitMaxDiffChars = it }))
                .applyToComponent { name = "commit.maxDiffChars" }
                .comment(AgenstormBundle.message("settings.commit.maxDiffChars.comment"))
        }
        panel.row {
            checkBox(AgenstormBundle.message("settings.commit.conventional"))
                .bindSelected({ state.commitConventionalCommits }, { state.commitConventionalCommits = it })
                .applyToComponent { name = "commit.conventional" }
        }
        panel.row {
            checkBox(AgenstormBundle.message("settings.commit.body"))
                .bindSelected({ state.commitBodyEnabled }, { state.commitBodyEnabled = it })
                .applyToComponent { name = "commit.body" }
        }
        panel.row(AgenstormBundle.message("settings.commit.language")) {
            textField()
                .bindText({ state.commitLanguage }, { state.commitLanguage = it.trim() })
                .applyToComponent { name = "commit.language" }
                .comment(AgenstormBundle.message("settings.commit.language.comment"))
        }
        promptRow(panel, "settings.commit.systemPrompt", "commit.systemPrompt", PromptBuilder.DEFAULT_SYSTEM, { state.commitSystemPrompt }, { state.commitSystemPrompt = it })
        promptRow(panel, "settings.commit.userPrompt", "commit.userPrompt", PromptBuilder.DEFAULT_USER, { state.commitUserPrompt }, { state.commitUserPrompt = it })
    }

    /** Shows the effective template; a value equal to the built-in default is stored as "" (= default). */
    private fun promptRow(panel: Panel, labelKey: String, name: String, default: String, get: () -> String, set: (String) -> Unit) {
        panel.row(AgenstormBundle.message(labelKey)) {
            val area = textArea()
                .rows(4)
                .align(AlignX.FILL)
                .bindText({ get().ifBlank { default } }, { set(if (it.trim() == default.trim()) "" else it) })
                .applyToComponent { this.name = name }
                .component
            button(AgenstormBundle.message("settings.commit.resetPrompt")) { area.text = default }
                .applyToComponent { this.name = "$name.reset" }
        }
    }

    private fun testConnection() {
        val option = backendCombo.selectedItem as? BackendOption ?: return
        val model = modelField.text.trim()
        val backend: LlmBackend = when (option.id) {
            AnthropicBackend.ID -> AnthropicBackend(apiKey = { String(anthropicKeyField.password) }, defaultModel = model.ifEmpty { AnthropicBackend.DEFAULT_MODEL })
            OpenAiCompatibleBackend.ID -> OpenAiCompatibleBackend(apiKey = { String(openAiKeyField.password) }, baseUrl = baseUrlField.text.trim(), defaultModel = model)
            ClaudeCliBackend.ID -> ClaudeCliBackend(executable = { ClaudeCliBackend.discover(cliPathField.text) }, extraArgs = extraArgsField.text, defaultModel = model.ifEmpty { ClaudeCliBackend.DEFAULT_MODEL })
            else -> FakeBackend(delayMs = 0)
        }
        val label = testResult ?: return
        label.text = AgenstormBundle.message("settings.commit.test.running")
        scope.launch {
            val problem = try {
                backend.validate()
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            withContext(Dispatchers.EDT) {
                label.text = problem ?: AgenstormBundle.message("settings.commit.test.ok")
            }
        }
    }

    companion object {
        val BACKENDS: List<BackendOption> = listOf(
            BackendOption(AnthropicBackend.ID, AgenstormBundle.message("settings.commit.backend.anthropic")),
            BackendOption(OpenAiCompatibleBackend.ID, AgenstormBundle.message("settings.commit.backend.openai")),
            BackendOption(ClaudeCliBackend.ID, AgenstormBundle.message("settings.commit.backend.cli")),
            BackendOption(FakeBackend.ID, AgenstormBundle.message("settings.commit.backend.fake")),
        )
    }
}
