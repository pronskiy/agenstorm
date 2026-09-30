package com.pronskiy.agenstorm.worktrees

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Step T1.7: the project excludes both worktree folders under its own root. */
class WorktreeExcludePolicyTest : BasePlatformTestCase() {

    fun testBothWorktreeFoldersUnderTheProjectRootAreExcluded() {
        val base = FileUtil.toSystemIndependentName(project.basePath!!)

        val urls = WorktreeExcludePolicy(project).excludeUrlsForProject.toList()

        assertEquals(listOf("$base/.worktrees", "$base/.claude/worktrees").map(VfsUtilCore::pathToUrl), urls)
    }
}
