package com.pronskiy.agenstorm.worktrees.merge

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.pronskiy.agenstorm.core.AgenstormBundle
import javax.swing.JComponent

/**
 * Step T4.3. "Merge Back": what goes where — the branch's commits into its base, at the worktree the base is checked out
 * in, and the uncommitted changes committed first if there are any — and how: squash (the default) or rebase.
 */
class MergeBackDialog(project: Project, private val name: String, private val plan: MergePlan.Plan.Ready) : DialogWrapper(project) {

    var strategy: MergePlan.Strategy = MergePlan.Strategy.SQUASH

    init {
        title = AgenstormBundle.message("worktrees.merge.title")
        setOKButtonText(AgenstormBundle.message("worktrees.merge.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(AgenstormBundle.message("worktrees.merge.summary", plan.commits, plan.branch, plan.base, FileUtil.toSystemDependentName(plan.baseWorktree))) }
        if (plan.commitFirst) row { label(AgenstormBundle.message("worktrees.merge.commitFirst", plan.changes.size, MergePlan.commitMessage(name))) }
        // Each radio bound on its own: `ButtonsGroup.bind` is an inline function compiled for JVM 25.
        buttonsGroup(AgenstormBundle.message("worktrees.merge.strategy")) {
            row {
                radioButton(AgenstormBundle.message("worktrees.merge.squash", plan.base))
                    .bindSelected({ strategy == MergePlan.Strategy.SQUASH }, { if (it) strategy = MergePlan.Strategy.SQUASH })
            }
            row {
                radioButton(AgenstormBundle.message("worktrees.merge.rebase", plan.base))
                    .bindSelected({ strategy == MergePlan.Strategy.REBASE }, { if (it) strategy = MergePlan.Strategy.REBASE })
            }
        }
    }
}
