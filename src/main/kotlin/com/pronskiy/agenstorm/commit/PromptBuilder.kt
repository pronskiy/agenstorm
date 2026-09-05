package com.pronskiy.agenstorm.commit

import com.pronskiy.agenstorm.commit.llm.LlmRequest

/** Everything a prompt can mention. Empty [branch]/[hint] are rendered as explicit placeholders. */
data class PromptContext(
    val diff: String,
    val stat: String,
    val branch: String = "",
    val hint: String = "",
    val language: String = "",
    val conventionalCommits: Boolean = true,
)

/**
 * Plain `{var}` substitution over two templates (system and user). Variables: `{diff}`, `{stat}`,
 * `{branch}`, `{hint}`, `{language}` (expands to "Write in X." or nothing) and `{conventional}`
 * (expands to [CONVENTIONAL_TEXT] or nothing). Unknown placeholders are left untouched. No template engine.
 */
class PromptBuilder(
    private val systemTemplate: String = DEFAULT_SYSTEM,
    private val userTemplate: String = DEFAULT_USER,
) {

    fun buildSystem(context: PromptContext): String =
        substitute(systemTemplate, variables(context)).replace(MULTIPLE_SPACES, " ").trim()

    fun buildUser(context: PromptContext): String = substitute(userTemplate, variables(context)).trim()

    fun build(context: PromptContext, model: String?, maxTokens: Int = 1024): LlmRequest =
        LlmRequest(buildSystem(context), buildUser(context), model?.takeIf { it.isNotBlank() }, maxTokens)

    private fun variables(context: PromptContext): Map<String, String> = mapOf(
        "diff" to context.diff,
        "stat" to context.stat,
        "branch" to context.branch.trim().ifEmpty { "(unknown)" },
        "hint" to context.hint.trim().ifEmpty { "(none)" },
        "language" to languageText(context.language),
        "conventional" to if (context.conventionalCommits) CONVENTIONAL_TEXT else "",
    )

    companion object {
        const val CONVENTIONAL_TEXT = "Use Conventional Commits (`type(scope): subject`)."

        val DEFAULT_SYSTEM: String by lazy { resource("/prompts/system.txt") }
        val DEFAULT_USER: String by lazy { resource("/prompts/user.txt") }

        private val PLACEHOLDER = Regex("\\{([A-Za-z]+)}")
        private val MULTIPLE_SPACES = Regex(" {2,}")

        fun languageText(language: String): String = language.trim().let { if (it.isEmpty()) "" else "Write in $it." }

        private fun substitute(template: String, variables: Map<String, String>): String =
            PLACEHOLDER.replace(template) { match -> variables[match.groupValues[1]] ?: match.value }

        private fun resource(path: String): String =
            PromptBuilder::class.java.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }?.trimEnd()
                ?: error("Missing prompt template $path")
    }
}
