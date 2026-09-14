package com.pronskiy.agenstorm.projectview

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.ClientProperty
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.core.AgenstormBundle
import java.awt.BorderLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener
import javax.swing.tree.TreePath

/**
 * Step O1.2. The editable row: an icon and a text field drawn on the project tree itself.
 *
 * It is a child of the [JTree] rather than a `TreeCellEditor` (decision 52) — `JTree` is a `Container` with a
 * null layout, which is how Swing hosts its own editing component, so an absolutely positioned child scrolls
 * with the tree and disturbs neither the model nor the row count. Nothing below it moves; while the field is
 * open it covers one row, which is the compromise the placement rule is chosen to make least surprising.
 *
 * It owns focus, so the four handlers already installed on the project tree — double-click-to-open,
 * Enter-to-open, the speed search and the copy/paste Escape handler — never see a keystroke meant for it.
 */
class InlineNameEditor private constructor(
    private val tree: JTree,
    private val anchor: TreePath,
    private val placement: Placement,
    private val kind: InlineNameKind,
    private val isDirectory: Boolean,
    private val siblingNames: Set<String>,
    private val onCommit: (String) -> Unit,
) : Disposable {

    private val icon = JBLabel().apply { border = JBUI.Borders.emptyRight(4) }
    private val field = JBTextField()
    private val row = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = UIUtil.getTreeBackground()
        border = JBUI.Borders.empty(0, 2)
        add(icon, BorderLayout.WEST)
        add(field, BorderLayout.CENTER)
    }

    /** Set before any commit or cancel, so the focus listener cannot re-enter while a refactoring runs. */
    private var closing = false

    /** One natural row, measured before the spacer doubles the anchor's height. */
    private var rowHeight = 0

    /** The tree's own renderer, put back when the field closes. Null while no gap is open. */
    private var displacedRenderer: javax.swing.tree.TreeCellRenderer? = null

    private val viewportListener = javax.swing.event.ChangeListener { repositionOrCancel() }

    private val modelListener = object : TreeModelListener {
        override fun treeNodesChanged(e: TreeModelEvent) { repositionOrCancel() }
        override fun treeNodesInserted(e: TreeModelEvent) { repositionOrCancel() }
        override fun treeNodesRemoved(e: TreeModelEvent) { repositionOrCancel() }
        override fun treeStructureChanged(e: TreeModelEvent) { repositionOrCancel() }
    }

    private val focusListener = object : FocusAdapter() {
        override fun focusLost(e: FocusEvent) {
            // Finder's rule: clicking away accepts what was typed. A temporary loss is a popup, not a click away.
            if (!e.isTemporary) commit()
        }
    }

    private fun install(initialText: String, selectionEnd: Int) {
        field.font = tree.font
        field.background = UIUtil.getTreeBackground()
        field.text = initialText
        // A zero-width selection is a caret: that is how a template entry opens on `.php` with nothing selected
        // and the caret in front of the dot, ready for the name.
        field.select(0, selectionEnd.coerceIn(0, initialText.length))
        if (selectionEnd == 0) field.caretPosition = 0
        updateIcon()
        validateNow()

        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                updateIcon()
                validateNow()
            }
        })

        // Registered on the field, not through its InputMap: IdeKeyEventDispatcher sees a key first, and Escape
        // inside a tool window is otherwise claimed by ToolWindowManager before the field ever hears it.
        DumbAwareAction.create { commit() }.registerCustomShortcutSet(CommonShortcuts.ENTER, field, this)
        DumbAwareAction.create { cancel() }.registerCustomShortcutSet(CommonShortcuts.ESCAPE, field, this)

        field.addFocusListener(focusListener)
        tree.model?.addTreeModelListener(modelListener)
        viewportOf()?.addChangeListener(viewportListener)

        rowHeight = ProjectTreeAccess.boundsOf(tree, anchor)?.height ?: 0
        if (rowHeight <= 0) {
            cancel()
            return
        }
        openTheGap()

        tree.add(row)
        if (!reposition()) {
            cancel()
            return
        }
        focusTheField()
    }

    /**
     * Doubles the anchor's row so the rows below move down and the field has somewhere to be. A rename edits
     * the anchor's own row, so it needs no gap.
     */
    private fun openTheGap() {
        if (placement == Placement.OVER_ANCHOR) return
        val current = tree.cellRenderer ?: return
        displacedRenderer = current
        tree.cellRenderer = SpacerRenderer(current, anchor, rowHeight)
    }

    /** Closes the gap again. Safe to call when none was opened. */
    private fun closeTheGap() {
        val original = displacedRenderer ?: return
        displacedRenderer = null
        if (tree.cellRenderer is SpacerRenderer) tree.cellRenderer = original
    }

    /**
     * `requestFocusInWindow` is asynchronous, and the tree's speed search turns the first keystroke into a
     * search popup if focus has not landed yet — so it is asked for twice.
     */
    private fun focusTheField() {
        field.requestFocusInWindow()
        ApplicationManager.getApplication().invokeLater({
            if (!closing && !field.hasFocus()) field.requestFocusInWindow()
        }, { closing })
    }

    /** The row the anchor moved under, or nothing left to sit on — a collapsed parent cancels rather than lingers. */
    private fun repositionOrCancel() {
        if (!closing && !reposition()) cancel()
    }

    /** Puts the row where it belongs. `false` means the anchor is gone and the field has nothing to sit on. */
    private fun reposition(): Boolean {
        if (closing) return false
        val anchorBounds = ProjectTreeAccess.boundsOf(tree, anchor) ?: return false
        val bounds = InlineRowGeometry.place(
            anchor = anchorBounds,
            placement = placement,
            indentPerLevel = ProjectTreeAccess.indentPerLevel(tree),
            viewport = tree.visibleRect,
            rightGap = JBUI.scale(8),
            minWidth = JBUI.scale(120),
            rowHeight = rowHeight,
        )
        row.bounds = bounds
        tree.scrollRectToVisible(bounds)
        row.revalidate()
        tree.repaint()
        return true
    }

    private fun updateIcon() {
        val name = field.text.trim()
        icon.icon = when {
            isDirectory || kind == InlineNameKind.NEW_DIRECTORY -> AllIcons.Nodes.Folder
            // Until there is an extension every name is "unknown", which flickers; hold the plain-file icon.
            !name.contains('.') -> AllIcons.FileTypes.Text
            else -> FileTypeRegistry.getInstance().getFileTypeByFileName(name).icon
        }
    }

    private fun verdict(): NameVerdict = InlineNamePolicy.validate(kind, field.text, siblingNames)

    private fun validateNow() {
        when (val v = verdict()) {
            is NameVerdict.Ok -> {
                field.foreground = UIUtil.getTreeForeground()
                field.toolTipText = null
            }

            is NameVerdict.Invalid -> {
                field.foreground = JBColor.RED
                field.toolTipText = AgenstormBundle.message(v.messageKey, v.arg ?: "")
            }
        }
    }

    /** Accepts what was typed. An unusable name is dropped rather than argued with — nothing is created. */
    fun commit() {
        if (closing) return
        val typed = field.text.trim()
        val usable = verdict() is NameVerdict.Ok
        close()
        if (usable) onCommit(typed)
    }

    fun cancel() {
        if (closing) return
        close()
    }

    private fun close() {
        closing = true
        field.removeFocusListener(focusListener)
        Disposer.dispose(this)
    }

    private fun viewportOf(): JViewport? = SwingUtilities.getAncestorOfClass(JViewport::class.java, tree) as? JViewport

    override fun dispose() {
        closing = true
        field.removeFocusListener(focusListener)
        closeTheGap()
        tree.model?.removeTreeModelListener(modelListener)
        viewportOf()?.removeChangeListener(viewportListener)
        if (row.parent === tree) {
            tree.remove(row)
            tree.repaint()
        }
        if (ClientProperty.get(tree, OPEN_EDITOR) === this) ClientProperty.put(tree, OPEN_EDITOR, null)
        // Focus goes back where it came from, or the tree is left with nothing selected-looking.
        if (!tree.hasFocus()) tree.requestFocusInWindow()
    }

    companion object {

        private val OPEN_EDITOR: Key<InlineNameEditor> = Key.create("agenstorm.projectview.inlineNameEditor")

        /**
         * Opens the field against [anchor]. A field already open on this tree is committed first, so a second
         * invocation replaces it rather than stacking a second overlay on top.
         *
         * Returns `null` when the anchor has no bounds — collapsed, or not laid out by the async model yet —
         * which is the caller's signal to fall back to the action it displaced.
         */
        @JvmStatic
        fun open(
            tree: JTree,
            anchor: TreePath,
            placement: Placement,
            kind: InlineNameKind,
            initialText: String,
            isDirectory: Boolean,
            siblingNames: Set<String>,
            /** How much of [initialText] is selected; `0` puts the caret at the front and selects nothing. */
            selectionEnd: Int = InlineNamePolicy.selectionEnd(initialText, isDirectory),
            onCommit: (String) -> Unit,
        ): InlineNameEditor? {
            ClientProperty.get(tree, OPEN_EDITOR)?.commit()
            if (ProjectTreeAccess.boundsOf(tree, anchor) == null) return null
            val editor = InlineNameEditor(tree, anchor, placement, kind, isDirectory, siblingNames, onCommit)
            ClientProperty.put(tree, OPEN_EDITOR, editor)
            editor.install(initialText, selectionEnd)
            return if (editor.closing) null else editor
        }

        /** Closes whatever is open on [tree]; the plugin's unload path, so no live component outlives it. */
        @JvmStatic
        fun closeOn(tree: JTree) {
            ClientProperty.get(tree, OPEN_EDITOR)?.cancel()
        }
    }
}
