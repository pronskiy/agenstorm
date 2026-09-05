package com.pronskiy.agenstorm.commit.context

import com.intellij.openapi.project.Project
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Step D3.2: the branch name for `{branch}` comes from the first provider that knows one; none → "". */
class CommitContextTest : BasePlatformTestCase() {

    fun testNoRepositoryMeansEmptyBranch() {
        assertEquals("", CommitContext.branchName(project))
    }

    fun testFirstProviderWithABranchWins() {
        val silent = object : CommitContextProvider {
            override fun branchName(project: Project): String? = null
        }
        val loud = object : CommitContextProvider {
            override fun branchName(project: Project): String? = "feature/x"
        }
        ExtensionTestUtil.maskExtensions(CommitContextProvider.EP_NAME, listOf(silent, loud), testRootDisposable)

        assertEquals("feature/x", CommitContext.branchName(project))
    }

    fun testTheBuiltInProviderIsRegistered() {
        assertTrue(CommitContextProvider.EP_NAME.extensionList.any { it is GitCommitContextProvider })
    }
}
