package com.pronskiy.agenstorm.tabs

import java.nio.file.Path

/**
 * The names of a list of projects shown at once — the tabs and the "+" popup — told apart: a name two entries
 * share is shown with its parent folder in front, `SomeFolder/infrastructure`, so same-named projects in
 * different folders can be chosen without opening them (Igor Voitovich, 2026-10-21). A name used once is left
 * exactly as it is.
 *
 * Each entry of a shared name goes up to the nearest folder no other entry of that name has at the same distance,
 * and no further: `x/dev/app` and `y/dev/app`, whose parents are both `dev`, beside `other/app`, which one folder
 * already tells apart. Two or more folders between that one and the name collapse into `…`, so the `fix-login`
 * worktrees of `/w/A/app` and `/w/B/app` read `A/…/app:fix-login`, not `A/app/.worktrees/app:fix-login`.
 *
 * Pure: entries in, names out; the callers read the `tabsDisambiguateNames` setting themselves.
 */
object ProjectNameUniquifier {

    /** One entry of the shown list: [key] is what the map returns a name for, [name] is the name to show when it stands
     * alone, [path] the project's folder, used for the prefixes. */
    data class Entry(val key: String, val name: String, val path: String?)

    /** The name to show for every entry: [Entry.name] when unique, [name] prefixed with parent folders when not. */
    fun displayNames(entries: List<Entry>): Map<String, String> {
        val names = LinkedHashMap<String, String>(entries.size)
        for ((name, group) in entries.groupBy { it.name }) {
            if (group.size == 1) {
                names[group.first().key] = name
                continue
            }
            val parents = group.map { ancestors(it.path) }
            val depths = parents.mapIndexed { i, own -> depth(own, parents.filterIndexed { j, _ -> j != i }) }
            val short = parents.mapIndexed { i, own -> prefixed(own, depths[i], name, collapse = true) }
            group.forEachIndexed { i, entry ->
                // A collapsed name another collapsed name happens to equal shows every folder instead.
                names[entry.key] = if (short.count { it == short[i] } > 1) prefixed(parents[i], depths[i], name, collapse = false) else short[i]
            }
        }
        return names
    }

    /** How many of [own]'s folders, nearest first, it takes to reach one that no list of [others] has at the same
     * place; all of them when none does. */
    private fun depth(own: List<String>, others: List<List<String>>): Int =
        own.indices.firstOrNull { i -> others.none { it.getOrNull(i) == own[i] } }?.plus(1) ?: own.size

    /** The [name] with the [depth] nearest of [parents] in front, innermost last: `x/dev/app` for depth 2. With
     * [collapse], the folders between the outermost one and the name become `…` when there are two or more. */
    private fun prefixed(parents: List<String>, depth: Int, name: String, collapse: Boolean): String {
        val shown = parents.take(depth)
        val folders = if (collapse && shown.size > 2) listOf(shown.last(), "…") else shown.asReversed()
        return (folders + name).joinToString("/")
    }

    /** The parent folder names of [path], nearest first; empty when there are none. The file system root is not a name. */
    private fun ancestors(path: String?): List<String> =
        path?.let { p -> Path.of(p).parent }?.let { parent ->
            generateSequence(parent) { it.parent }
                .takeWhile { it.parent != null }
                .map { it.fileName.toString() }
                .toList()
        }.orEmpty()
}
