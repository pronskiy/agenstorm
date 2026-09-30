package com.pronskiy.agenstorm.worktrees.carry

import java.nio.file.Path

/**
 * Step T2.2, pure. What a new worktree gets from the main checkout: the untracked files and folders that match
 * `.worktreeinclude` (gitignore syntax; `.env*` when there is no such file) **and** that git ignores — the semantics
 * Claude Code, Conductor and VS Code share (decision 79). Git does the matching: the candidates come from
 * `git ls-files --others --ignored --exclude-from=.worktreeinclude --directory`, the ignored ones from the same with
 * `--exclude-standard`, and this object intersects the two. A candidate counts as ignored when git lists it or a
 * folder above it; a folder git does not ignore as a whole is left out. Nothing under [skip] is ever carried — the
 * worktree folders themselves, `.idea` (T2.4) and `.git`.
 */
object CarryPlan {

    const val INCLUDE_FILE = ".worktreeinclude"
    val DEFAULT_PATTERNS = listOf(".env*")
    val ALWAYS_SKIPPED = listOf(".git", ".idea")

    fun candidateArgs(includeFile: Path?): List<String> =
        listOf("--others", "--ignored", "--directory", "-z") +
            (includeFile?.let { listOf("--exclude-from=$it") } ?: DEFAULT_PATTERNS.map { "--exclude=$it" })

    val IGNORED_ARGS = listOf("--others", "--ignored", "--directory", "--exclude-standard", "-z")

    /** `git ls-files -z` output, whichever way the handler split it. */
    fun split(output: List<String>): List<String> = output.flatMap { it.split('\u0000') }.map { it.trim('\n', '\r') }.filter { it.isNotEmpty() }

    /** Relative paths to copy, folders without their trailing slash, sorted. */
    fun entries(candidates: List<String>, ignored: List<String>, skip: List<String>): List<String> {
        val ignoredEntries = ignored.toSet()
        val ignoredFolders = ignored.filter { it.endsWith("/") }
        val skipped = (skip + ALWAYS_SKIPPED).map { it.trim('/') }.filter { it.isNotEmpty() }
        return candidates
            .filter { candidate -> candidate in ignoredEntries || ignoredFolders.any { candidate.startsWith(it) } }
            .map { it.trimEnd('/') }
            .filterNot { entry -> skipped.any { entry == it || entry.startsWith("$it/") } }
            .distinct()
            .sorted()
    }
}
