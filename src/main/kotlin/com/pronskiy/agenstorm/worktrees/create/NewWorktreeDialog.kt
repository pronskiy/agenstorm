package com.pronskiy.agenstorm.worktrees.create

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.worktrees.WorktreeExcludes
import javax.swing.JComponent

/**
 * Step T2.1 (minimal). "New Worktree": the name, and what to branch off — the current HEAD by default (decision 80),
 * or the default branch when the repository has one. The folder and branch the name turns into are shown as the name
 * is typed; a name that is empty or taken is refused before anything runs.
 */
class NewWorktreeDialog(
    project: Project,
    private val currentBranch: String?,
    private val defaultBranch: String?,
    private val taken: Set<String>,
) : DialogWrapper(project) {

    var name: String = ""
    var fromDefaultBranch: Boolean = false

    init {
        title = AgenstormBundle.message("worktrees.new.title")
        setOKButtonText(AgenstormBundle.message("worktrees.new.create"))
        init()
    }

    val slug: String? get() = WorktreeNames.slug(name)

    override fun createCenterPanel(): JComponent = panel {
        row(AgenstormBundle.message("worktrees.new.name")) {
            textField()
                .bindText(::name)
                .focused()
                .columns(COLUMNS_MEDIUM)
                .comment(AgenstormBundle.message("worktrees.new.name.comment", WorktreeExcludes.DEFAULT_FOLDER))
                .validationOnApply { field: JBTextField -> validate(field.text) }
                .validationOnInput { field: JBTextField -> validate(field.text) }
        }
        // Each radio bound on its own: `ButtonsGroup.bind` is an inline function compiled for JVM 25.
        buttonsGroup(AgenstormBundle.message("worktrees.new.base")) {
            row {
                radioButton(AgenstormBundle.message("worktrees.new.base.head", currentBranch ?: "HEAD"))
                    .bindSelected({ !fromDefaultBranch }, { if (it) fromDefaultBranch = false })
            }
            if (defaultBranch != null) row {
                radioButton(AgenstormBundle.message("worktrees.new.base.default", defaultBranch))
                    .bindSelected({ fromDefaultBranch }, { if (it) fromDefaultBranch = true })
            }
        }
    }

    private fun validate(text: String): ValidationInfo? {
        val slug = WorktreeNames.slug(text) ?: return ValidationInfo(AgenstormBundle.message("worktrees.new.error.empty"))
        return if (slug in taken) ValidationInfo(AgenstormBundle.message("worktrees.new.error.exists", slug)) else null
    }
}
