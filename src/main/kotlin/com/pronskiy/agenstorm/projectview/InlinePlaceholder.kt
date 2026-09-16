package com.pronskiy.agenstorm.projectview

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.NodeSortOrder
import com.intellij.ide.projectView.NodeSortSettings
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory

/** Where the row being named sits among its folder's children. */
enum class PlaceholderPosition {
    /** Clicked on the folder itself: the row is right under it, as its first child. */
    FIRST,

    /** Clicked on a file: the row is right under that file. */
    AFTER_ANCHOR,

    /** Invoked from a menu with no click to be below: the row is the folder's last child. */
    LAST,
}

/**
 * Decision 61. The row a name is being typed into, as a real node in the project tree.
 *
 * The project view's rows come from its structure, and every row is exactly `getRowHeight()` tall — so the only
 * way to make the tree move its other rows apart is to give it one more child. This is that child, added by
 * [InlinePlaceholderProvider] to [directory] for as long as the field is open. The instance is the node's value,
 * so a refresh that rebuilds the folder rebuilds an *equal* node and the tree keeps its row.
 *
 * [anchor] is the node that was clicked, for [PlaceholderPosition.AFTER_ANCHOR]; its sort keys are borrowed so
 * the tree's own comparator puts the row directly below it in any sort mode.
 */
class InlinePlaceholder(
    val project: Project,
    val directory: VirtualFile,
    val position: PlaceholderPosition,
    val anchor: ProjectViewNode<*>?,
    val isDirectory: Boolean,
)

/** The one row being named, if any. Read on the background threads the tree builds its structure on. */
object InlinePlaceholders {

    @Volatile
    var pending: InlinePlaceholder? = null
        private set

    fun show(placeholder: InlinePlaceholder) {
        pending = placeholder
    }

    /** Clears [placeholder], or whatever is pending when called without one. */
    fun clear(placeholder: InlinePlaceholder? = null) {
        if (placeholder == null || pending === placeholder) pending = null
    }
}

/**
 * Registered in `plugin.xml` as a `treeStructureProvider`. Adds the pending [InlinePlaceholder] to its folder and
 * leaves every other node exactly as the project view built it. Called for every expanded node on every refresh,
 * so the no-op path is two field reads.
 */
class InlinePlaceholderProvider : TreeStructureProvider, DumbAware {

    override fun modify(
        parent: AbstractTreeNode<*>,
        children: Collection<AbstractTreeNode<*>>,
        settings: ViewSettings?,
    ): Collection<AbstractTreeNode<*>> {
        val placeholder = InlinePlaceholders.pending ?: return children
        if (parent.project != placeholder.project) return children
        val directory = (parent.value as? PsiDirectory)?.virtualFile ?: return children
        if (directory != placeholder.directory) return children
        return children + PlaceholderNode(placeholder, settings)
    }
}

/**
 * The placeholder's node. It has no children and never contains a file, so selection, navigation and "select
 * opened file" all pass it by. The field is drawn over its row.
 */
class PlaceholderNode(
    private val placeholder: InlinePlaceholder,
    settings: ViewSettings?,
) : ProjectViewNode<InlinePlaceholder>(placeholder.project, placeholder, settings) {

    override fun getChildren(): Collection<AbstractTreeNode<*>> = emptyList()

    override fun contains(file: VirtualFile): Boolean = false

    override fun isAlwaysLeaf(): Boolean = true

    override fun canNavigate(): Boolean = false

    override fun canNavigateToSource(): Boolean = false

    override fun update(presentation: PresentationData) {
        presentation.setIcon(if (placeholder.isDirectory) AllIcons.Nodes.Folder else AllIcons.FileTypes.Text)
    }

    // --- sorting ----------------------------------------------------------------------------------------------
    //
    // `GroupByTypeComparator` compares, in order: getSortOrder, the manual-order key, the folders-on-top weight,
    // the key for the active sort mode, then AlphaComparator — getWeight, then toString through
    // FileNameComparator. FIRST and LAST win at the first step. AFTER_ANCHOR ties with the anchor at every step
    // and loses by a hair at the last: its name is the anchor's plus the lowest character there is.

    private val anchor: ProjectViewNode<*>?
        get() = placeholder.anchor.takeIf { placeholder.position == PlaceholderPosition.AFTER_ANCHOR }

    override fun getSortOrder(settings: NodeSortSettings): NodeSortOrder = when (placeholder.position) {
        PlaceholderPosition.FIRST -> NodeSortOrder.entries.first()
        PlaceholderPosition.LAST -> NodeSortOrder.entries.last()
        PlaceholderPosition.AFTER_ANCHOR -> anchor?.getSortOrder(settings) ?: super.getSortOrder(settings)
    }

    override fun getManualOrderKey(): Comparable<*>? = anchor?.manualOrderKey

    override fun getTypeSortWeight(sortByType: Boolean): Int = anchor?.getTypeSortWeight(sortByType) ?: 0

    override fun getTypeSortKey(): Comparable<*>? = anchor?.typeSortKey

    override fun getSortKey(): Comparable<*>? = anchor?.sortKey

    override fun getTimeSortKey(): Comparable<*>? = anchor?.timeSortKey

    override fun getQualifiedNameSortKey(): String? = anchor?.qualifiedNameSortKey

    override fun getWeight(): Int = anchor?.weight ?: super.getWeight()

    override fun toString(): String = anchor?.let { it.toString() + LOWEST_CHAR } ?: ""

    private companion object {
        val LOWEST_CHAR: Char = Char(0)
    }
}
