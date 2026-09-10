package com.pronskiy.agenstorm.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.annotations.VisibleForTesting

/**
 * Step K1.4. One `$EDITOR` invocation from an IDE terminal: the file opens in this project's window, and the
 * caller — blocked on the endpoint's answer all the while — is let go when the last editor for that file
 * closes.
 *
 * Closing the tab is the whole "I am done" gesture, the one `--wait` has meant on every JetBrains launcher
 * for years. It cannot come any earlier: the caller reads the file back the instant this request is answered
 * (Claude Code with `spawnSync` + `readFileSync`, `git` with the message file), so answering while the tab is
 * still open would hand it the text as it was before the edit.
 */
class TerminalEditSession(private val project: Project) {

    /**
     * Opens [path] and suspends until it is closed again. True means the round trip happened and the file on
     * disk is what the user left behind; false means the IDE will not take this one, which the shim turns
     * into a real editor rather than into an error.
     */
    suspend fun editAndAwaitClose(path: Path): Boolean {
        if (project.isDisposed) return false
        // A temp file the caller has just written is a file the IDE has never seen. The refresh must not run
        // under a read lock, which is why the endpoint calls this off the EDT.
        val file = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) }
        if (file == null) {
            LOG.debug("Agenstorm: $path is not in the VFS, leaving it to the shim's own editor")
            return false
        }
        val closed = CompletableDeferred<Unit>()
        // Subscribed before the file is opened: a listener registered afterwards could miss the close.
        val connection = project.messageBus.connect()
        val steppedAside = stepAside()
        try {
            connection.subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun fileClosed(source: FileEditorManager, closedFile: VirtualFile) {
                        // A file open in two splits is closed once per split; only the last one means done.
                        if (closedFile == file && !source.isFileOpen(file)) closed.complete(Unit)
                    }
                },
            )
            if (!open(file)) return false
            closed.await()
            save(file)
            awaitBytesOnDisk(file, path)
            return true
        } finally {
            connection.disconnect()
            restore(steppedAside)
        }
    }

    /**
     * Step K1.7. A maximized terminal *is* the editor's area, so a file opened behind it is a file nobody
     * can see — Roman's first run: "when the terminal is maximized, I don't see the plan unless I manually
     * minimize terminal". The terminal steps aside for the edit, and [restore] puts it back.
     *
     * Whatever maximized it counts, this feature's own toggle or the platform's Ctrl+Shift+': what matters
     * is that the editor has no room, not who took it.
     */
    private suspend fun stepAside(): Boolean = withContext(Dispatchers.EDT) {
        if (project.isDisposed) return@withContext false
        val terminal = TerminalMaximizeToggleAction.terminalOf(project) ?: return@withContext false
        if (!TerminalMaximizeToggleAction.stateOf(project, terminal).isTerminalMaximized) return@withContext false
        ToolWindowManager.getInstance(project).setMaximized(terminal, false)
        true
    }

    /**
     * The terminal takes its place back once the tab is closed — but only the terminal *we* moved, and only
     * if it is still where we left it. Someone who hides the terminal, floats it or maximizes it again while
     * the file is open has said what they want more recently than we did.
     *
     * [NonCancellable] because a layout Agenstorm changed is a layout Agenstorm gives back, even when the
     * request it was changed for is being torn down.
     */
    private suspend fun restore(steppedAside: Boolean) {
        if (!steppedAside) return
        withContext(NonCancellable + Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            val terminal = TerminalMaximizeToggleAction.terminalOf(project) ?: return@withContext
            if (!shouldRestore(TerminalMaximizeToggleAction.stateOf(project, terminal))) return@withContext
            ToolWindowManager.getInstance(project).setMaximized(terminal, true)
        }
    }

    private suspend fun open(file: VirtualFile): Boolean = withContext(Dispatchers.EDT) {
        if (project.isDisposed) return@withContext false
        // What the caller hands over is usually a temp file outside every content root, and editing one of
        // those is exactly what the non-project-file protection asks about. Running the command *is* the
        // answer to that question, so it is not worth asking again — and a caller waiting on a file the user
        // cannot type into would wait for a very long time.
        NonProjectFileWritingAccessProvider.allowWriting(listOf(file))
        OpenFileDescriptor(project, file).navigate(true)
        FileEditorManager.getInstance(project).isFileOpen(file)
    }

    private suspend fun save(file: VirtualFile) = withContext(Dispatchers.EDT) {
        if (project.isDisposed) return@withContext
        val documents = FileDocumentManager.getInstance()
        val document = documents.getDocument(file) ?: return@withContext
        // Closing a tab leaves the document modified in memory, and the caller reads the file from disk as
        // soon as this request is answered — so what was typed has to be on disk before that happens.
        ApplicationManager.getApplication().runWriteAction { documents.saveDocument(document) }
    }

    /**
     * Saving is where this feature could quietly lose someone's work. `saveDocument` returns once the VFS
     * holds the new content, and the bytes reach the file system after that — the difference is normally
     * imperceptible, and it was measured here at a few milliseconds. But the caller reads the file the
     * instant this request is answered, so those milliseconds are the whole window in which it reads an
     * empty file and takes it for an edit: the plan, the prompt or the commit message, gone.
     *
     * So the answer waits for the bytes. [VirtualFile.getLength] is what the VFS recorded for the save and
     * the file's size only ever reaches it once the write is through, which needs no charset or line
     * separator to be guessed at. Never longer than [FLUSH_BUDGET_MS]: a caller kept waiting forever would
     * be worse than one that reads a file the IDE could not flush.
     */
    private suspend fun awaitBytesOnDisk(file: VirtualFile, path: Path) = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + FLUSH_BUDGET_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            val onDisk = try {
                Files.size(path)
            } catch (_: IOException) {
                return@withContext
            }
            if (onDisk == file.length) return@withContext
            delay(FLUSH_POLL_MS)
        }
        LOG.warn("Agenstorm: $path was still not ${file.length} bytes on disk ${FLUSH_BUDGET_MS} ms after saving it")
    }

    companion object {
        /**
         * Whether the terminal we un-maximized may be maximized again: it is still docked, still on screen,
         * and nobody has maximized it in the meantime. Pure, so the "never fight the user" rule is testable.
         */
        @VisibleForTesting
        fun shouldRestore(state: TerminalMaximizeToggleAction.TerminalWindowState): Boolean =
            state.visible && state.docked && !state.maximized

        private val LOG = logger<TerminalEditSession>()

        /** Measured at a few milliseconds; the budget is generous because overshooting it costs nothing. */
        private const val FLUSH_BUDGET_MS = 2_000L
        private const val FLUSH_POLL_MS = 5L
    }
}
