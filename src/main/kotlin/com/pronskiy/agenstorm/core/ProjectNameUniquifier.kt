package com.pronskiy.agenstorm.core

import java.nio.file.Path

/**
 * The names of a list of projects shown at once — the tabs and the "+" popup — told apart: a name two entries
 * share is shown with its parent folder in front, `SomeFolder/infrastructure`, so same-named projects in
 * different folders can be chosen without opening them (Igor Voitovich, 2026-10-21). A name used once is left
 * exactly as it is, and a name shared in the same parent folder — which only deeper paths can tell apart — grows
 * prefixes until it does, `x/dev/app` against `y/dev/app`.
 *
 * Pure: entries in, names out; the callers read [AgenstormSettings.State.tabsDisambiguateNames] themselves.
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
            val depth = group.map { ancestors(it.path).size }.maxOrNull() ?: 0
            var labels = group.associate { it.key to name }
            for (d in 1..depth) {
                labels = group.associate { it.key to prefixed(it.path, d, name) }
                if (labels.values.distinct().size == group.size) break
            }
            names.putAll(labels)
        }
        return names
    }

    /** The [name] with the [depth] nearest parent folders in front, innermost last: `x/dev/app` for depth 3.
     * Fewer parents than that prefix what there is: `/app` has none and keeps the name alone. */
    private fun prefixed(path: String?, depth: Int, name: String): String =
        (ancestors(path).take(depth).asReversed() + name).joinToString("/")

    /** The parent folder names of [path], nearest first; empty when there are none. The file system root is not a name. */
    private fun ancestors(path: String?): List<String> =
        path?.let { p -> Path.of(p).parent }?.let { parent ->
            generateSequence(parent) { it.parent }
                .takeWhile { it.parent != null }
                .map { it.fileName.toString() }
                .toList()
        }.orEmpty()
}
