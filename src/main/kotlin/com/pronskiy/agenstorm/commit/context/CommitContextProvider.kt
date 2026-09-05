package com.pronskiy.agenstorm.commit.context

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/** Supplies repository facts for the prompt (`{branch}`). Registered under `com.pronskiy.agenstorm.commitContextProvider`. */
interface CommitContextProvider {

    /** Current branch name, or null when this provider does not know. Called in a read action off the EDT. */
    fun branchName(project: Project): String?

    companion object {
        val EP_NAME: ExtensionPointName<CommitContextProvider> = ExtensionPointName.create("com.pronskiy.agenstorm.commitContextProvider")
    }
}

object CommitContext {

    /** The first non-blank branch name any provider reports; "" when none does (the prompt renders "(unknown)"). */
    fun branchName(project: Project): String =
        CommitContextProvider.EP_NAME.extensionList.firstNotNullOfOrNull { provider ->
            provider.branchName(project)?.takeIf { it.isNotBlank() }
        } ?: ""
}
