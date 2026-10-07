package com.pronskiy.agenstorm.markdown.toc

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarComponent
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.ThreadingAssertions
import com.pronskiy.agenstorm.core.AgenstormBundle
import com.pronskiy.agenstorm.core.AgenstormSettings
import java.awt.Point
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly

/** What the contents widget shows: the listed entries, the current one's index (-1 for none), and whether it shows at all. */
data class TocState(val visible: List<TocEntry>, val current: Int, val shows: Boolean) {
    companion object {
        val EMPTY = TocState(emptyList(), -1, false)
    }
}

/**
 * Step W2.2. The contents of one Markdown editor: collected at once and again [DEBOUNCE_MS] after the last edit, the
 * current section re-read on every scroll and resize, the result kept in [state] for the action's BGT update and
 * pushed to the panel through [subscribe]. Made by [TocService] when the editor's floating toolbar registers our
 * provider and disposed with that toolbar, so it lives exactly as long as the editor has the widget's slot.
 */
@OptIn(FlowPreview::class)
class TocController(
    val editor: EditorEx,
    private val project: Project,
    scope: CoroutineScope,
    val toolbar: FloatingToolbarComponent? = null,
    debounceMs: Long = DEBOUNCE_MS,
) : Disposable {

    /** What the fit rule needs from the editor, in pixels and columns. */
    data class Viewport(val width: Int, val height: Int, val marginColumns: Int, val spaceWidth: Int)

    @Volatile
    var state: TocState = TocState.EMPTY
        private set

    /** Every heading of the file, before the depth filter; EDT only. */
    private var entries: List<TocEntry> = emptyList()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val recollect = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile
    private var disposed = false
    private val job: Job

    init {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                recollect.tryEmit(Unit)
            }
        }, this)
        editor.scrollingModel.addVisibleAreaListener({ refresh() }, this)
        editor.addPropertyChangeListener({ if (it.propertyName == EditorEx.PROP_FONT_SIZE) notifyListeners() }, this)
        job = scope.launch(CoroutineName("Agenstorm contents")) {
            collect()
            recollect.debounce(debounceMs).collectLatest { collect() }
        }
    }

    /** [listener] runs on the EDT after every state or viewport change, until the returned handle is disposed. */
    fun subscribe(listener: () -> Unit): Disposable {
        listeners += listener
        return Disposable { listeners -= listener }
    }

    /** Re-reads the headings off the EDT, after pending commits, and applies them on the EDT. */
    suspend fun collect() {
        val collected = constrainedReadAction(ReadConstraint.withDocumentsCommitted(project)) {
            if (disposed || editor.isDisposed) null else psiFile()?.let(TocCollector::collect)
        } ?: return
        withContext(Dispatchers.EDT) { setEntries(collected) }
    }

    /** Synchronous variant for callers already on the EDT with committed documents. */
    @TestOnly
    fun collectNow() {
        ThreadingAssertions.assertEventDispatchThread()
        setEntries(psiFile()?.let(TocCollector::collect) ?: emptyList())
    }

    /** Settings, viewport or headings changed: recompute what is listed and which entry is current. EDT. */
    fun refresh() {
        if (disposed || editor.isDisposed) return
        val settings = AgenstormSettings.getInstance().state
        val visible = TocOutline.visible(entries, settings.markdownTocDepth)
        val next = TocState(visible, TocOutline.current(visible, topLine()), settings.markdownTocEnabled && TocOutline.shows(visible))
        val showsChanged = next.shows != state.shows
        state = next
        // The toolbar re-runs the action's update on its own timer; ask at once so showing and hiding are not late.
        if (showsChanged) (toolbar as? ActionToolbar)?.updateActionsAsync()
        notifyListeners()
    }

    /**
     * Step W2.4. The caret to the heading, the heading near the top of the view (one line of air above it), the move
     * recorded as a navigation so Back returns, and the focus back in the editor. EDT.
     */
    fun navigate(entry: TocEntry) {
        if (disposed || editor.isDisposed || project.isDisposed) return
        val offset = entry.offset.coerceIn(0, editor.document.textLength)
        CommandProcessor.getInstance().executeCommand(project, {
            editor.selectionModel.removeSelection()
            editor.caretModel.moveToOffset(offset)
            editor.scrollingModel.scrollVertically((editor.offsetToXY(offset).y - editor.lineHeight).coerceAtLeast(0))
            IdeDocumentHistory.getInstance(project).includeCurrentCommandAsNavigation()
        }, AgenstormBundle.message("markdown.toc.navigate"), null)
        IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
    }

    /** EDT. */
    fun viewport(): Viewport {
        val area = editor.scrollingModel.visibleArea
        return Viewport(area.width, area.height, editor.settings.getRightMargin(project), EditorUtil.getPlainSpaceWidth(editor))
    }

    override fun dispose() {
        disposed = true
        job.cancel()
        listeners.clear()
    }

    private fun setEntries(collected: List<TocEntry>) {
        if (disposed || editor.isDisposed) return
        entries = collected
        refresh()
    }

    private fun notifyListeners() {
        if (disposed) return
        for (listener in listeners) listener()
    }

    /** The first logical line in view, read half a line below the top edge so a heading scrolled half out still counts. */
    private fun topLine(): Int {
        val area = editor.scrollingModel.visibleArea
        return editor.xyToLogicalPosition(Point(0, area.y + editor.lineHeight / 2)).line
    }

    private fun psiFile(): PsiFile? = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)

    companion object {
        const val DEBOUNCE_MS = 300L
    }
}
