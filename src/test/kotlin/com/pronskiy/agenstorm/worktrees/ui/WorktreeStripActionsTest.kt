package com.pronskiy.agenstorm.worktrees.ui

import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.WorktreeTab
import java.awt.datatransfer.DataFlavor

/** Step T1.5: a worktree tab's menu; T4.1: Remove on every tab but the main checkout's. */
class WorktreeStripActionsTest : BasePlatformTestCase() {

    private fun tab(current: Boolean, main: Boolean = false) =
        WorktreeTab(Worktree(if (main) "/r" else "/r/.worktrees/fix-login", "fix/login", isMain = main, isLocked = false, lockReason = null, createdAt = 1), "fix-login", current)

    private fun texts(current: Boolean, main: Boolean = false) =
        WorktreeStripActions.contextMenuGroup(project, tab(current, main)).getChildren(null).map { if (it is Separator) "---" else it.templatePresentation.text }

    fun testAnotherWorktreeCanBeOpenedCopiedOpenedInTheTerminalAndRemoved() {
        assertEquals(listOf("Open in New Window", "Copy Path", "Open in Terminal", "---", "Merge Back\u2026", "Archive Worktree\u2026", "Remove Worktree\u2026"), texts(current = false))
    }

    fun testTheCurrentWorktreeHasNoOpenInNewWindow() {
        assertEquals(listOf("Copy Path", "Open in Terminal", "---", "Merge Back\u2026", "Archive Worktree\u2026", "Remove Worktree\u2026"), texts(current = true))
    }

    fun testTheMainCheckoutCannotBeRemoved() {
        assertEquals(listOf("Open in New Window", "Copy Path", "Open in Terminal"), texts(current = false, main = true))
    }

    fun testCopyPathPutsThePathOnTheClipboard() {
        WorktreeStripActions.copyPath("/r/.worktrees/fix-login")

        assertEquals(FileUtil.toSystemDependentName("/r/.worktrees/fix-login"), CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor))
    }
}
