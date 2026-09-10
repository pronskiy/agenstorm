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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

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
        } finally {
            connection.disconnect()
        }
        save(file)
        awaitBytesOnDisk(file, path)
        return true
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

    private companion object {
        private val LOG = logger<TerminalEditSession>()

        /** Measured at a few milliseconds; the budget is generous because overshooting it costs nothing. */
        private const val FLUSH_BUDGET_MS = 2_000L
        private const val FLUSH_POLL_MS = 5L
    }
}
