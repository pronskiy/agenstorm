package com.pronskiy.agenstorm.commit

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.PatchLine
import com.intellij.openapi.diff.impl.patch.TextFilePatch
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vfs.limits.FileSizeLimit
import java.io.IOException
import java.io.StringWriter
import java.nio.file.Paths

/** What goes into the prompt: one stat line per file, the budgeted unified diff, and the files kept out of it. */
data class CollectedDiff(val stat: String, val diff: String, val omittedFiles: List<String>)

/**
 * Turns the included changes into a ranked, budgeted unified diff plus a stat block. Ranking: project
 * sources, then tests, then docs/config; lock files and generated output never enter the diff. Every
 * file appears in the stat (`M src/Foo.php (+12 -3)`); binary, too-large, generated and over-budget files
 * are stat-only. Call from a background thread inside a read action; never throws.
 */
class DiffCollector(
    private val project: Project,
    private val maxDiffChars: Int = DEFAULT_MAX_DIFF_CHARS,
    private val perFileCap: Int = DEFAULT_PER_FILE_CAP,
    /** Directory paths are made relative to; defaults to the project base path. */
    private val basePath: String? = project.basePath,
) {

    fun collect(changes: Collection<Change>, unversioned: Collection<FilePath> = emptyList()): CollectedDiff {
        val all = changes + unversioned.map { Change(null, CurrentContentRevision(it)) }
        val entries = all.map(::describe).sortedBy { it.category.ordinal }
        val stat = ArrayList<String>(entries.size)
        val diff = StringBuilder()
        val omitted = ArrayList<String>()

        for (entry in entries) {
            val skip = when {
                entry.category == Category.GENERATED -> "generated, not in diff"
                entry.binary -> "binary"
                entry.tooLarge -> "too large"
                else -> null
            }
            if (skip != null) {
                stat += "${entry.status} ${entry.path} ($skip)"
                omitted += entry.path
                continue
            }
            val patch = patchText(entry.change)
            if (patch == null) {
                stat += "${entry.status} ${entry.path} (diff unavailable)"
                omitted += entry.path
                continue
            }
            val counts = "+${patch.added} -${patch.removed}"
            val text = cap(patch.text)
            if (diff.length + text.length > maxDiffChars) {
                stat += "${entry.status} ${entry.path} ($counts, over budget, not in diff)"
                omitted += entry.path
                continue
            }
            diff.append(text)
            if (!text.endsWith("\n")) diff.append('\n')
            stat += "${entry.status} ${entry.path} ($counts)"
        }
        return CollectedDiff(stat.joinToString("\n"), diff.toString().trimEnd(), omitted)
    }

    private enum class Category { SOURCE, TEST, DOCS_CONFIG, GENERATED }

    private class Entry(val change: Change, val path: String, val status: String, val category: Category, val binary: Boolean, val tooLarge: Boolean)

    private class PatchText(val text: String, val added: Int, val removed: Int)

    private fun describe(change: Change): Entry {
        val revision: ContentRevision = change.afterRevision ?: change.beforeRevision!!
        val path = relativePath(revision.file)
        val status = when (change.type) {
            Change.Type.NEW -> "A"
            Change.Type.DELETED -> "D"
            Change.Type.MOVED -> "R"
            else -> "M"
        }
        val length = revision.file.virtualFile?.length
        val tooLarge = length != null && FileSizeLimit.isTooLargeForContentLoading(length, revision.file.name.substringAfterLast('.', ""))
        return Entry(change, path, status, categorize(path), IdeaTextPatchBuilder.isBinaryRevision(revision), tooLarge)
    }

    private fun relativePath(file: FilePath): String {
        val path = FileUtil.toSystemIndependentName(file.path)
        val base = basePath?.let(FileUtil::toSystemIndependentName) ?: return path
        return FileUtil.getRelativePath(base, path, '/') ?: path
    }

    private fun patchText(change: Change): PatchText? = try {
        val base = Paths.get(basePath ?: "")
        val patches = IdeaTextPatchBuilder.buildPatch(project, listOf(change), base, false, true)
        val writer = StringWriter()
        // No PatchEPs: the default CharsetEP refreshes the VFS synchronously, which is forbidden under a read lock.
        UnifiedDiffWriter.write(project, base, patches, writer, "\n", null, emptyList())
        val lines = patches.filterIsInstance<TextFilePatch>().flatMap { it.hunks }.flatMap { it.lines }
        PatchText(writer.toString(), lines.count { it.type == PatchLine.Type.ADD }, lines.count { it.type == PatchLine.Type.REMOVE })
    } catch (e: VcsException) {
        LOG.info("Cannot build a patch for ${change.afterRevision?.file ?: change.beforeRevision?.file}", e)
        null
    } catch (e: IOException) {
        LOG.info("Cannot write a patch for ${change.afterRevision?.file ?: change.beforeRevision?.file}", e)
        null
    }

    /** Keeps the first [perFileCap] characters (cut at a line break) and says how many lines were dropped. */
    private fun cap(text: String): String {
        if (text.length <= perFileCap) return text
        val cut = text.lastIndexOf('\n', perFileCap).takeIf { it > 0 } ?: perFileCap
        val dropped = text.substring(cut).count { it == '\n' }
        return text.substring(0, cut) + "\n... [truncated $dropped lines]\n"
    }

    companion object {
        const val DEFAULT_MAX_DIFF_CHARS = 60_000
        const val DEFAULT_PER_FILE_CAP = 8_000

        private val LOG = logger<DiffCollector>()
        private val LOCK_FILES = setOf("composer.lock", "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "cargo.lock", "gemfile.lock", "poetry.lock", "go.sum")
        private val GENERATED_DIRS = setOf("vendor", "node_modules", "dist", "build")
        private val GENERATED_NAME = Regex(".*\\.min\\.[a-z0-9]+$|.*\\.map$|.*\\.snap$")
        private val TEST_DIR = Regex("(^|/)(tests?|__tests__|spec)/")
        private val TEST_NAME = Regex(".*(Test|Spec|\\.test|\\.spec|_test)\\.[a-z0-9]+$")
        private val DOCS_CONFIG_EXTENSIONS = setOf("md", "markdown", "txt", "rst", "json", "yaml", "yml", "xml", "toml", "ini", "properties", "conf", "cfg")

        private fun categorize(path: String): Category {
            val lower = path.lowercase()
            val name = lower.substringAfterLast('/')
            val segments = lower.split('/').dropLast(1)
            if (name in LOCK_FILES || GENERATED_NAME.matches(name) || segments.any { it in GENERATED_DIRS }) return Category.GENERATED
            if (TEST_DIR.containsMatchIn(lower) || TEST_NAME.matches(name)) return Category.TEST
            if (name.startsWith(".env") || name.substringAfterLast('.', "") in DOCS_CONFIG_EXTENSIONS) return Category.DOCS_CONFIG
            return Category.SOURCE
        }
    }
}
