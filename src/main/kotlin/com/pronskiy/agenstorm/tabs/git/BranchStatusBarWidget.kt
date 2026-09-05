package com.pronskiy.agenstorm.tabs.git

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.ide.ui.UISettingsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
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
import com.intellij.openapi.application.ModalityState
import java.awt.BorderLayout
import java.awt.Container
import java.awt.Cursor
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Step E3.2. The Git branch of the repository behind the focused file (else the project's first repository),
 * shown in the status bar. The platform gets an invisible [host] as the widget's component (so its own layout
 * bookkeeping never touches the visible part); the visible [label] is placed by [BranchWidgetPlacement] into the
 * status bar's left panel, or into [host] where that is impossible. Refreshes on repository changes and editor
 * switches; a click opens the branches popup.
 */
class BranchStatusBarWidget(private val project: Project) : CustomStatusBarWidget {

    private var statusBar: StatusBar? = null
    private var placement: BranchWidgetPlacement.Attachment? = null
    private val host = JPanel(BorderLayout()).apply {
        isOpaque = false
        isVisible = false
    }
    internal val label = JBLabel(AllIcons.Vcs.Branch).apply {
        border = JBUI.Borders.empty(0, JBUI.scale(DEFAULT_STRIPE_WIDTH), 0, RIGHT_PADDING)
        iconTextGap = JBUI.scale(4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) showPopup(e)
            }
        })
        // Pixel alignment with the tool window above: pad the icon out to the tool window stripe's right edge,
        // whatever inset the status bar itself adds. Re-done whenever the label is shown or moved; converges.
        addHierarchyListener { if (it.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) alignWithToolWindowStripe() }
        addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = alignWithToolWindowStripe()
        })
    }

    override fun ID(): String = BranchStatusBarWidgetFactory.ID

    override fun getComponent(): JComponent = host

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val connection = project.messageBus.connect(this)
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { refresh() })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = refresh()
        })
        // The status bar adds the host to its ordinary widget area after install(); place the label once that is
        // done, and again after UI settings changes in case the platform rebuilt its left panel.
        place()
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(UISettingsListener.TOPIC, UISettingsListener { place() })
        refresh()
    }

    override fun dispose() {
        placement?.let(BranchWidgetPlacement::detach)
        placement = null
        statusBar = null
    }

    private fun place() {
        ApplicationManager.getApplication().invokeLater({
            val bar = statusBar ?: return@invokeLater
            if (project.isDisposed) return@invokeLater
            if (label.parent == null || placement == null) placement = BranchWidgetPlacement.attach(bar.component, host, label)
        }, ModalityState.any())
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

    /** Left padding so the icon starts where the tool window content starts: at the left stripe's right edge. */
    private fun alignWithToolWindowStripe() {
        if (!label.isShowing) return
        val root = SwingUtilities.getRoot(label) as? Container ?: return
        val stripeRight = toolWindowStripeRightEdge(root) ?: JBUI.scale(DEFAULT_STRIPE_WIDTH)
        val labelX = SwingUtilities.convertPoint(label, 0, 0, root).x
        val left = alignmentPadding(stripeRight, labelX)
        val current = label.insets.left
        if (left != current) {
            label.border = JBUI.Borders.empty(0, left, 0, RIGHT_PADDING)
            label.revalidate()
            label.repaint()
        }
    }

    companion object {
        private val LOG = logger<BranchStatusBarWidget>()
        const val BRANCHES_ACTION = "Git.Branches"
        /** Width of the new UI's tool window stripe, used until the real one has been measured. */
        const val DEFAULT_STRIPE_WIDTH = 40
        private val RIGHT_PADDING = JBUI.scale(8)

        /** Padding that puts content starting at [labelX] onto [stripeRight]; never negative, never absurd. */
        fun alignmentPadding(stripeRight: Int, labelX: Int): Int = (stripeRight - labelX).coerceIn(0, JBUI.scale(80))

        /**
         * The right edge (in [root] coordinates) of the leftmost tool window stripe, found through its buttons
         * (any visible component whose class name ends with `StripeButton`, sitting in a container at the left edge).
         * A plain sequence, not the tree traverser's `map`, which demands a reversible mapping.
         */
        internal fun toolWindowStripeRightEdge(root: Container): Int? {
            val button = UIUtil.uiTraverser(root).asSequence().firstOrNull { component ->
                val parent = component.parent
                component.isVisible && component.javaClass.simpleName.endsWith("StripeButton") && parent != null &&
                    SwingUtilities.convertPoint(parent, 0, 0, root).x <= JBUI.scale(8)
            } ?: return null
            val stripe = button.parent
            return SwingUtilities.convertPoint(stripe, stripe.width, 0, root).x
        }

        /** Branch name, else the first 8 characters of a detached revision, else a "no branch" text. */
        fun textFor(branch: String?, revision: String?): String =
            branch?.takeIf { it.isNotBlank() } ?: revision?.takeIf { it.isNotBlank() }?.take(8) ?: AgenstormBundle.message("tabs.branch.noBranch")
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
        val actionEvent = AnActionEvent.createEvent(action, DataManager.getInstance().getDataContext(label), null, ActionPlaces.STATUS_BAR_PLACE, ActionUiKind.NONE, event)
        ActionUtil.performAction(action, actionEvent)
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
