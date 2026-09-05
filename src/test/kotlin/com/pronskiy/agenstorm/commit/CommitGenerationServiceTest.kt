package com.pronskiy.agenstorm.commit

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.pronskiy.agenstorm.commit.llm.FakeBackend
import com.pronskiy.agenstorm.core.AgenstormSettings
import kotlinx.coroutines.Job

/**
 * Steps D1.4/D1.6: the generation pipeline streams FakeBackend chunks into the commit message field, runs the
 * post-processor at the end, keeps everything in one undo step, and restores the hint on failure.
 */
class CommitGenerationServiceTest : BasePlatformTestCase() {

    private lateinit var commitMessage: CommitMessage

    override fun setUp() {
        super.setUp()
        commitMessage = CommitMessage(project)
    }

    override fun tearDown() {
        try {
            AgenstormSettings.getInstance().loadState(AgenstormSettings.State())
            commitMessage.dispose()
        } finally {
            super.tearDown()
        }
    }

    fun testStreamsChunksAndPostProcessesIntoTheField() {
        val service = project.service<CommitGenerationService>()
        val backend = FakeBackend(listOf("feat: a", "dd thing\n", "\nBody."), delayMs = 0)

        val job = service.generate(commitMessage, listOf(change()), emptyList(), backend)
        await(job)

        assertEquals("feat: add thing\n\nBody.", commitMessage.text)
        assertFalse(service.isRunning)
    }

    fun testWholeGenerationIsOneUndoStep() {
        val service = project.service<CommitGenerationService>()
        commitMessage.text = "my hint"
        // Headless EditorTextField has no editor; undo needs a FileEditor over the same document.
        val editor = EditorFactory.getInstance().createEditor(commitMessage.editorField.document, project)
        try {
            val fileEditor = TextEditorProvider.getInstance().getTextEditor(editor)
            val undoManager = UndoManager.getInstance(project)

            await(service.generate(commitMessage, listOf(change()), emptyList(), FakeBackend(listOf("feat: a", "dd thing\n", "\nBody."), delayMs = 0)))
            assertEquals("feat: add thing\n\nBody.", commitMessage.text)

            assertTrue(undoManager.isUndoAvailable(fileEditor))
            undoManager.undo(fileEditor)
            assertEquals("my hint", commitMessage.text)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testFailureRestoresTheHint() {
        val service = project.service<CommitGenerationService>()
        commitMessage.text = "my hint"

        await(service.generate(commitMessage, listOf(change()), emptyList(), FakeBackend(listOf("partial"), delayMs = 0, failure = "boom")))

        assertEquals("my hint", commitMessage.text)
        assertFalse(service.isRunning)
    }

    fun testCancelStopsTheStreamAndKeepsPartialText() {
        val service = project.service<CommitGenerationService>()
        val backend = FakeBackend(listOf("feat: slow", " chunk", " never"), delayMs = 400)

        val job = service.generate(commitMessage, listOf(change()), emptyList(), backend)
        awaitUntil { commitMessage.text.isNotEmpty() }
        assertTrue(service.isRunning)
        service.cancel()
        await(job)

        assertTrue(commitMessage.text.startsWith("feat: slow"))
        assertFalse(commitMessage.text.contains("never"))
        assertFalse(service.isRunning)
    }

    private fun change(): Change {
        val path = LocalFilePath(project.basePath + "/src/Foo.php", false)
        return Change(SimpleContentRevision("<?php\na\n", path, "HEAD"), SimpleContentRevision("<?php\nb\n", path, "WORKING"))
    }

    private fun await(job: Job) = awaitUntil { job.isCompleted }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            assertTrue("timed out waiting", System.currentTimeMillis() < deadline)
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
    }
}
