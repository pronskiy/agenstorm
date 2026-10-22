package com.pronskiy.agenstorm.tabs

import com.pronskiy.agenstorm.core.ProjectName
import java.nio.file.Path

/**
 * The names of a list of projects shown at once — the tabs and the "+" popup — told apart: a project whose name another
 * project of the list shares is shown with its parent folder in front, `SomeFolder/infrastructure`, so same-named
 * projects in different folders can be chosen without opening them (Igor Voitovich, 2026-10-21). A name used once is
 * left exactly as it is.
 *
 * What is told apart is projects, not entries: a linked worktree counts as its main checkout ([ProjectName.projectPath])
 * and carries that one's folder, `SomeFolder/infrastructure:fix`, never its own `.worktrees/` (Roman, 2026-10-22). So
 * `happy` and `happy:test` stay as they are, while beside another `infrastructure` the worktree of one is told apart
 * even when it is shown without its main checkout.
 *
 * Each project of a shared name goes up to the nearest folder no other project of that name has at the same distance,
 * and no further: `x/dev/app` and `y/dev/app`, whose parents are both `dev`, beside `other/app`, which one folder
 * already tells apart. Two or more folders between that one and the name collapse into `…`: `A/…/app` for
 * `/w/A/src/main/app` beside `/w/B/src/main/app`.
 *
 * Pure: entries in, names out; the callers read the `tabsDisambiguateNames` setting themselves.
 */
object ProjectNameUniquifier {

    /** One entry of the shown list: [key] is what the map returns a name for, [name] what it is called and where. */
    data class Entry(val key: String, val name: ProjectName)

    /** The name to show for every entry: [ProjectName.text], with parent folders in front when its project's name is shared. */
    fun displayNames(entries: List<Entry>): Map<String, String> {
        val names = LinkedHashMap<String, String>(entries.size)
        for (group in entries.groupBy { it.name.project }.values) {
            val folders = group.map { folder(it.name.projectPath) }.distinct()
            val prefixes = if (folders.size > 1) prefixes(folders) else emptyMap()
            for (entry in group) names[entry.key] = prefixes[folder(entry.name.projectPath)].orEmpty() + entry.name.text
        }
        return names
    }

    /** The prefix of each of [folders], same-named projects all. */
    private fun prefixes(folders: List<String?>): Map<String?, String> {
        val parents = folders.map(::ancestors)
        val depths = parents.mapIndexed { i, own -> depth(own, parents.filterIndexed { j, _ -> j != i }) }
        val short = parents.mapIndexed { i, own -> prefix(own, depths[i], collapse = true) }
        // A collapsed prefix another collapsed prefix happens to equal shows every folder instead.
        return folders.indices.associate { i ->
            folders[i] to if (short.count { it == short[i] } > 1) prefix(parents[i], depths[i], collapse = false) else short[i]
        }
    }

    /** How many of [own]'s folders, nearest first, it takes to reach one that no list of [others] has at the same
     * place; all of them when none does. */
    private fun depth(own: List<String>, others: List<List<String>>): Int =
        own.indices.firstOrNull { i -> others.none { it.getOrNull(i) == own[i] } }?.plus(1) ?: own.size

    /** The [depth] nearest of [parents], outermost first, each followed by `/`: `x/dev/` for depth 2. With [collapse],
     * the folders between the outermost one and the name become `…` when there are two or more. */
    private fun prefix(parents: List<String>, depth: Int, collapse: Boolean): String {
        val shown = parents.take(depth)
        val folders = if (collapse && shown.size > 2) listOf(shown.last(), "…") else shown.asReversed()
        return folders.joinToString("") { "$it/" }
    }

    /** [path] in one spelling, so `/w/app/` and `/w/app` are one project. */
    private fun folder(path: String?): String? = path?.let { runCatching { Path.of(it).toString() }.getOrDefault(it) }

    /** The parent folder names of [path], nearest first; empty when there are none. The file system root is not a name. */
    private fun ancestors(path: String?): List<String> =
        path?.let { p -> runCatching { Path.of(p).parent }.getOrNull() }?.let { parent ->
            generateSequence(parent) { it.parent }
                .takeWhile { it.parent != null }
                .map { it.fileName.toString() }
                .toList()
        }.orEmpty()
}
