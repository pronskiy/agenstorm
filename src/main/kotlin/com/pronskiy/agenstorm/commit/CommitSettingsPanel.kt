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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JLabel

/**
 * The rows of the "Commit messages" settings group. API keys come from [ApiKeyStore] and never touch
 * `agenstorm.xml`: PasswordSafe forbids keychain access on the EDT, so the fields start empty, [loadStoredKeys]
 * fills them from a background coroutine once the panel is built, and a changed key is written back off the EDT
 * on apply. "Test Connection" runs the selected backend's `validate()` with the values currently typed (not yet
 * applied) on [scope]. Call [dispose] when the page closes.
 */
class CommitSettingsPanel(private val scope: CoroutineScope) {

    private val state: AgenstormSettings.State
        get() = AgenstormSettings.getInstance().state

    private var anthropicKey: String = ""
    private var openAiKey: String = ""
    /** What PasswordSafe holds, once known: null until [loadStoredKeys] has answered or a save has happened. */
    private var storedAnthropicKey: String? = null
    private var storedOpenAiKey: String? = null
    private var keyLoad: Job? = null
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
                .onApply { saveKey(AnthropicBackend.ID, anthropicKey, storedAnthropicKey) { storedAnthropicKey = it } }
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
                .onApply { saveKey(OpenAiCompatibleBackend.ID, openAiKey, storedOpenAiKey) { storedOpenAiKey = it } }
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
        loadStoredKeys()
    }

    /** Cancels a key load still in flight; the page is closing and nobody is waiting for the fields. */
    fun dispose() {
        keyLoad?.cancel()
        keyLoad = null
    }

    /**
     * Reads both keys off the EDT and shows them without marking the page modified (the bound property moves
     * together with the field). A key the user typed before the answer arrived wins; a key saved meanwhile too.
     */
    private fun loadStoredKeys() {
        keyLoad = scope.launch {
            val anthropic = ApiKeyStore.load(AnthropicBackend.ID) ?: ""
            val openAi = ApiKeyStore.load(OpenAiCompatibleBackend.ID) ?: ""
            withContext(Dispatchers.EDT) {
                if (storedAnthropicKey == null) {
                    storedAnthropicKey = anthropic
                    anthropicKey = anthropic
                    if (anthropicKeyField.password.isEmpty()) anthropicKeyField.text = anthropic
                }
                if (storedOpenAiKey == null) {
                    storedOpenAiKey = openAi
                    openAiKey = openAi
                    if (openAiKeyField.password.isEmpty()) openAiKeyField.text = openAi
                }
            }
        }
    }

    /** Writes a key that differs from the stored one, off the EDT; an unchanged key costs no keychain round trip. */
    private fun saveKey(backendId: String, key: String, stored: String?, remember: (String) -> Unit) {
        if (key == (stored ?: "")) return
        remember(key)
        scope.launch { ApiKeyStore.store(backendId, key) }
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
