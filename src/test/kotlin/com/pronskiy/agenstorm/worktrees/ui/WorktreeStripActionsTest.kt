package com.pronskiy.agenstorm.worktrees.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pronskiy.agenstorm.worktrees.Worktree
import com.pronskiy.agenstorm.worktrees.WorktreeTab
import java.awt.datatransfer.DataFlavor

/** Step T1.5: a worktree tab's menu. */
class WorktreeStripActionsTest : BasePlatformTestCase() {

    private fun tab(current: Boolean) =
        WorktreeTab(Worktree("/r/.worktrees/fix-login", "fix/login", isMain = false, isLocked = false, lockReason = null, createdAt = 1), "fix-login", current)

    private fun texts(current: Boolean) = WorktreeStripActions.contextMenuGroup(project, tab(current)).getChildren(null).map { it.templatePresentation.text }

    fun testAnotherWorktreeCanBeOpenedCopiedAndOpenedInTheTerminal() {
        assertEquals(listOf("Open in New Window", "Copy Path", "Open in Terminal"), texts(current = false))
    }

    fun testTheCurrentWorktreeHasNoOpenInNewWindow() {
        assertEquals(listOf("Copy Path", "Open in Terminal"), texts(current = true))
    }

    fun testCopyPathPutsThePathOnTheClipboard() {
        WorktreeStripActions.copyPath("/r/.worktrees/fix-login")

        assertEquals(FileUtil.toSystemDependentName("/r/.worktrees/fix-login"), CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor))
    }
}
