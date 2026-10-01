package com.pronskiy.agenstorm.terminal.tmux

import com.intellij.ide.ProcessCloseConfirmation
import com.pronskiy.agenstorm.terminal.tmux.TmuxCloseDialog.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Steps U1.6 and U3.2: the platform's close dialog, rebuilt — its remembered choice and its buttons mean what they mean there — and the quit prompt. */
class TmuxCloseDialogTest {

    @Test
    fun theRememberedChoiceAnswersWithoutAsking() {
        assertNull(TmuxCloseDialog.remembered(ProcessCloseConfirmation.ASK, canKeep = true))
        assertEquals(Answer.TERMINATE, TmuxCloseDialog.remembered(ProcessCloseConfirmation.TERMINATE, canKeep = true))
        assertEquals(Answer.KEEP, TmuxCloseDialog.remembered(ProcessCloseConfirmation.DISCONNECT, canKeep = true))
        // On ✕ there is nothing to keep a session for, so a remembered Disconnect terminates — as the platform does.
        assertEquals(Answer.TERMINATE, TmuxCloseDialog.remembered(ProcessCloseConfirmation.DISCONNECT, canKeep = false))
    }

    @Test
    fun theButtonsAreTerminateThenDisconnectWhenOfferedThenCancel() {
        assertEquals(Answer.TERMINATE, TmuxCloseDialog.fromButton(0, canKeep = false))
        assertEquals(Answer.CANCEL, TmuxCloseDialog.fromButton(1, canKeep = false))
        assertEquals(Answer.KEEP, TmuxCloseDialog.fromButton(1, canKeep = true))
        assertEquals(Answer.CANCEL, TmuxCloseDialog.fromButton(2, canKeep = true))
        assertEquals(Answer.CANCEL, TmuxCloseDialog.fromButton(-1, canKeep = true)) // closed with Esc
    }

    @Test
    fun theQuitPromptIsKeepRunningThenStopAllThenCancel() {
        assertEquals(Answer.KEEP, TmuxCloseDialog.fromQuitButton(0))
        assertEquals(Answer.TERMINATE, TmuxCloseDialog.fromQuitButton(1))
        assertEquals(Answer.CANCEL, TmuxCloseDialog.fromQuitButton(2))
        assertEquals(Answer.CANCEL, TmuxCloseDialog.fromQuitButton(-1))
    }
}
