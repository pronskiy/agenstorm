package com.pronskiy.agenstorm.worktrees.create

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.worktrees.WorktreeExcludes
import java.awt.event.ItemEvent
import javax.swing.JComponent

/**
 * Step T2.1 (minimal). "New Worktree": the name, and what to branch off — the current HEAD by default (decision 80),
 * or the default branch when the repository has one. The folder and branch the name turns into are shown as the name
 * is typed; a name that is empty or taken is refused before anything runs. T4.2: or an existing branch no worktree has
 * checked out — how an archived worktree comes back — whose last segment fills in the name while none is typed.
 */
class NewWorktreeDialog(
    project: Project,
    private val currentBranch: String?,
    private val defaultBranch: String?,
    private val freeBranches: List<String>,
    private val taken: Set<String>,
) : DialogWrapper(project) {

    enum class Base { HEAD, DEFAULT_BRANCH, EXISTING_BRANCH }

    var name: String = ""
    var base: Base = Base.HEAD
    var branch: String? = freeBranches.firstOrNull()

    private lateinit var nameField: JBTextField
    private var lastSuggestion: String = ""

    init {
        title = AgenstormBundle.message("worktrees.new.title")
        setOKButtonText(AgenstormBundle.message("worktrees.new.create"))
        init()
    }

    val slug: String? get() = WorktreeNames.slug(name)

    override fun createCenterPanel(): JComponent = panel {
        row(AgenstormBundle.message("worktrees.new.name")) {
            nameField = textField()
                .bindText(::name)
                .focused()
                .columns(COLUMNS_MEDIUM)
                .comment(AgenstormBundle.message("worktrees.new.name.comment", WorktreeExcludes.configuredFolder()))
                .validationOnApply { field: JBTextField -> validate(field.text) }
                .validationOnInput { field: JBTextField -> validate(field.text) }
                .component
        }
        // Each radio bound on its own: `ButtonsGroup.bind` is an inline function compiled for JVM 25.
        buttonsGroup(AgenstormBundle.message("worktrees.new.base")) {
            row {
                radioButton(AgenstormBundle.message("worktrees.new.base.head", currentBranch ?: "HEAD"))
                    .bindSelected({ base == Base.HEAD }, { if (it) base = Base.HEAD })
            }
            if (defaultBranch != null) row {
                radioButton(AgenstormBundle.message("worktrees.new.base.default", defaultBranch))
                    .bindSelected({ base == Base.DEFAULT_BRANCH }, { if (it) base = Base.DEFAULT_BRANCH })
            }
            if (freeBranches.isNotEmpty()) row {
                val existing = radioButton(AgenstormBundle.message("worktrees.new.base.branch"))
                    .bindSelected({ base == Base.EXISTING_BRANCH }, { if (it) base = Base.EXISTING_BRANCH })
                comboBox(freeBranches)
                    .bindItem({ branch }, { branch = it })
                    .enabledIf(existing.component.selected)
                    .applyToComponent {
                        addItemListener { event -> if (event.stateChange == ItemEvent.SELECTED) suggestName(event.item as String) }
                    }
                existing.component.addItemListener { event ->
                    if (event.stateChange == ItemEvent.SELECTED) branch?.let(::suggestName)
                }
            }
        }
    }

    private fun suggestName(branch: String) {
        if (nameField.text.isBlank() || nameField.text == lastSuggestion) {
            lastSuggestion = WorktreeNames.fromBranch(branch).orEmpty()
            nameField.text = lastSuggestion
        }
    }

    private fun validate(text: String): ValidationInfo? {
        val slug = WorktreeNames.slug(text) ?: return ValidationInfo(AgenstormBundle.message("worktrees.new.error.empty"))
        return if (slug in taken) ValidationInfo(AgenstormBundle.message("worktrees.new.error.exists", slug)) else null
    }
}
