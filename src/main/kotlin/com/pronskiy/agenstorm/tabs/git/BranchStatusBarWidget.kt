package com.pronskiy.agenstorm.tabs.git

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.ide.ui.UISettingsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormSettings
import git4idea.branch.GitBranchUtil
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import git4idea.ui.branch.popup.GitBranchesTreePopupOnBackend
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * Step E3.2. The Git branch of the repository behind the focused file (else the project's first repository),
 * shown in the status bar. Owns its component so [BranchWidgetPlacement] can move it into the status bar's
 * left slot. Refreshes on repository changes and editor switches; a click opens the branches popup.
 */
class BranchStatusBarWidget(private val project: Project) : CustomStatusBarWidget {

    private var statusBar: StatusBar? = null
    private val label = JBLabel(AllIcons.Vcs.Branch).apply {
        border = JBUI.Borders.empty(0, 8)
        iconTextGap = JBUI.scale(4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) showPopup(e)
            }
        })
    }

    override fun ID(): String = BranchStatusBarWidgetFactory.ID

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val connection = project.messageBus.connect(this)
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { refresh() })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = refresh()
        })
        // The status bar has just added the component to its ordinary widget area; move it into the left slot now,
        // and again whenever the navigation bar setting changes (the platform re-takes the slot when it comes back).
        BranchWidgetPlacement.placeCentrally(statusBar, label)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            UISettingsListener.TOPIC,
            UISettingsListener { BranchWidgetPlacement.onUiSettingsChanged(project, statusBar, this) },
        )
        refresh()
    }

    override fun dispose() {
        statusBar?.let { BranchWidgetPlacement.clearCentral(it, label) }
        statusBar = null
    }

    /** The repository the widget describes right now. */
    fun repository(): GitRepository? {
        if (project.isDisposed) return null
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        val forFile = if (file != null) GitBranchUtil.guessWidgetRepository(project, file) else null
        return forFile ?: GitRepositoryManager.getInstance(project).repositories.firstOrNull()
    }

    /** Re-reads the branch on the EDT; cheap, so it runs on every repository event. */
    fun refresh() {
        UIUtil.invokeLaterIfNeeded {
            if (project.isDisposed) return@invokeLaterIfNeeded
            val repository = repository()
            label.text = textFor(repository?.currentBranchName, repository?.currentRevision)
            label.toolTipText = repository?.root?.presentableUrl?.let { AgenstormBundle.message("tabs.branch.tooltip", it) }
            label.isVisible = repository != null
            label.revalidate()
            label.repaint()
        }
    }

    private fun showPopup(event: MouseEvent) {
        val repository = repository() ?: return
        val popup = try {
            GitBranchesTreePopupOnBackend.create(project, repository)
        } catch (e: LinkageError) {
            LOG.warn("Branches popup class unavailable; falling back to the Git.Branches action", e)
            null
        }
        if (popup != null) {
            popup.showUnderneathOf(label)
            return
        }
        val action = ActionManager.getInstance().getAction(BRANCHES_ACTION) ?: return
        ActionUtil.invokeAction(action, DataManager.getInstance().getDataContext(label), ActionPlaces.STATUS_BAR_PLACE, event, null)
    }

    companion object {
        private val LOG = logger<BranchStatusBarWidget>()
        const val BRANCHES_ACTION = "Git.Branches"

        /** Branch name, else the first 8 characters of a detached revision, else a "no branch" text. */
        fun textFor(branch: String?, revision: String?): String =
            branch?.takeIf { it.isNotBlank() } ?: revision?.takeIf { it.isNotBlank() }?.take(8) ?: AgenstormBundle.message("tabs.branch.noBranch")
    }
}

/** Registered in `agenstorm-git.xml`; available while the feature is on and the project has Git repositories. */
class BranchStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = ID

    override fun getDisplayName(): String = AgenstormBundle.message("tabs.branch.widget.name")

    override fun isAvailable(project: Project): Boolean =
        isFeatureOn() && !project.isDisposed && GitRepositoryManager.getInstance(project).repositories.isNotEmpty()

    override fun createWidget(project: Project): StatusBarWidget = BranchStatusBarWidget(project)

    override fun isEnabledByDefault(): Boolean = true

    companion object {
        const val ID = "agenstorm.branch"

        fun isFeatureOn(): Boolean = AgenstormSettings.getInstance().state.let { it.projectTabsEnabled && it.branchInStatusBar }
    }
}
